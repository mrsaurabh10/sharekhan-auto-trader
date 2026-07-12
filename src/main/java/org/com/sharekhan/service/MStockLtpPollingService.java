package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockLtpPollingService {

    private final WebSocketSubscriptionService webSocketSubscriptionService;
    private final MStockLtpService mStockLtpService;
    private final LtpCacheService ltpCacheService;
    private final QuoteCacheService quoteCacheService;
    private final PriceTriggerService priceTriggerService;
    private final ScripExecutorManager scripExecutorManager;
    private final MStockInstrumentResolver instrumentResolver;
    private final TokenStoreService tokenStoreService;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
    private static final long MAX_TRANSIENT_BACKOFF_MS = 60_000L;

    private final Map<Integer, String> scripCodeToMStockKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> mStockKeyToScripCodeCache = new ConcurrentHashMap<>();
    private final AtomicBoolean afterHoursLogged = new AtomicBoolean(false);
    private final AtomicBoolean missingTokenLogged = new AtomicBoolean(false);
    private volatile Instant nextPollAttemptAt = Instant.EPOCH;
    private volatile int consecutiveTransientFailures = 0;

    @Value("${app.mstock.poll-delay-ms:1500}")
    private long mstockPollDelayMs;

    @Value("${app.market-data.sharekhan-quote-stale-ms:2000}")
    private long sharekhanQuoteStaleMs;

    @Scheduled(fixedDelayString = "${app.mstock.poll-delay-ms:1500}")
    public void pollMStockLtp() {
        try {
            ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
            LocalTime currentTime = now.toLocalTime();
            if (!currentTime.isBefore(MARKET_CLOSE_TIME)) {
                if (afterHoursLogged.compareAndSet(false, true)) {
                    log.info("Skipping MStock LTP polling after market close ({} IST)", currentTime);
                }
                return;
            } else {
                afterHoursLogged.set(false);
            }

            Set<String> activeScripKeys = webSocketSubscriptionService.getActiveScripKeys();
            if (activeScripKeys == null || activeScripKeys.isEmpty()) {
                return;
            }

            if (!hasMStockToken()) {
                if (missingTokenLogged.compareAndSet(false, true)) {
                    log.info("Skipping MStock LTP polling until a valid MStock access token is available.");
                }
                return;
            }
            missingTokenLogged.set(false);

            if (isInTransientBackoff(now.toInstant())) {
                return;
            }

            java.util.LinkedHashSet<String> instrumentSet = new java.util.LinkedHashSet<>();
            for (String scripKey : activeScripKeys) {
                Integer scripCode = extractScripCode(scripKey);
                if (scripCode == null) continue;

                if (hasFreshSharekhanQuote(scripCode)) {
                    log.debug("Skipping MStock LTP fallback for scrip {} because Sharekhan quote is fresh.", scripCode);
                    continue;
                }

                String mstockKey = getMStockKey(scripCode);
                if (StringUtils.hasText(mstockKey)) {
                    instrumentSet.add(mstockKey);
                }
            }

            if (instrumentSet.isEmpty()) {
                return;
            }

            Map<String, Map<String, Object>> ltpData = mStockLtpService.fetchLtp(new ArrayList<>(instrumentSet));
            if (ltpData == null || ltpData.isEmpty()) {
                resetTransientBackoffIfNeeded();
                return;
            }

            for (Map.Entry<String, Map<String, Object>> entry : ltpData.entrySet()) {
                String mstockKey = entry.getKey();
                Map<String, Object> data = entry.getValue();

                if (data == null) continue;
                Object priceObj = data.get("last_price");
                if (!(priceObj instanceof Number)) continue;

                double newLtp = ((Number) priceObj).doubleValue();
                Integer scripCode = mStockKeyToScripCodeCache.get(mstockKey);
                if (scripCode == null) continue;

                if (shouldTraceSensexMapping(mstockKey, scripCode)) {
                    log.info("MStock poll trace: scripCode={} mstockKey={} last_price={}",
                            scripCode, mstockKey, newLtp);
                }

                Double cachedLtp = ltpCacheService.getLtp(scripCode);
                if (cachedLtp != null && Double.compare(cachedLtp, newLtp) == 0) {
                    continue;
                }

                ltpCacheService.updateLtp(scripCode, newLtp);
                // Never run trigger/exit logic on the scheduler thread. A slow broker
                // request must not stall polling for every subscribed instrument.
                scripExecutorManager.submitTriggerTask(scripCode,
                        () -> priceTriggerService.evaluatePriceTrigger(scripCode, newLtp));
                scripExecutorManager.submitMonitorTask(scripCode,
                        () -> priceTriggerService.monitorOpenTrades(scripCode, newLtp));
            }
            resetTransientBackoffIfNeeded();
        } catch (MStockLtpException e) {
            if (e.isTransientFailure()) {
                applyTransientBackoff(e);
            } else {
                log.warn("Error during MStock LTP polling: {}", e.getMessage());
                log.debug("MStock LTP polling error trace", e);
            }
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("No MStock access token")) {
                if (missingTokenLogged.compareAndSet(false, true)) {
                    log.info("Skipping MStock LTP polling until a valid MStock access token is available.");
                }
            } else {
                log.warn("Error during MStock LTP polling: {}", e.getMessage());
                log.debug("MStock LTP polling error trace", e);
            }
        } catch (Exception e) {
            log.warn("Error during MStock LTP polling: {}", e.getMessage());
            log.debug("MStock LTP polling error trace", e);
        }
    }

    private boolean hasMStockToken() {
        return tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK) != null
                || StringUtils.hasText(tokenStoreService.getFirstNonExpiredTokenForBroker(Broker.MSTOCK))
                || StringUtils.hasText(tokenStoreService.getAccessToken(Broker.MSTOCK));
    }

    private boolean hasFreshSharekhanQuote(Integer scripCode) {
        if (quoteCacheService == null || scripCode == null) {
            return false;
        }
        try {
            Optional<QuoteCacheService.QuoteSnapshot> snapshotOpt = quoteCacheService.getSnapshot(scripCode);
            if (snapshotOpt == null || snapshotOpt.isEmpty()) {
                return false;
            }
            QuoteCacheService.QuoteSnapshot snapshot = snapshotOpt.get();
            if (quoteCacheService.isStale(snapshot, Duration.ofMillis(configuredSharekhanQuoteStaleMs()))) {
                return false;
            }
            return isUsablePrice(snapshot.getLastTradedPrice())
                    || isUsablePrice(snapshot.getMidPrice())
                    || isUsablePrice(snapshot.getBestBid())
                    || isUsablePrice(snapshot.getBestAsk());
        } catch (Exception e) {
            log.debug("Unable to inspect Sharekhan quote freshness for scrip {}: {}", scripCode, e.getMessage());
            return false;
        }
    }

    private long configuredSharekhanQuoteStaleMs() {
        return sharekhanQuoteStaleMs > 0 ? sharekhanQuoteStaleMs : 2000L;
    }

    private boolean isUsablePrice(Double price) {
        return price != null && Double.isFinite(price) && price > 0d;
    }

    private boolean isInTransientBackoff(Instant now) {
        return now != null && now.isBefore(nextPollAttemptAt);
    }

    private void applyTransientBackoff(MStockLtpException failure) {
        int failures = ++consecutiveTransientFailures;
        long baseDelay = Math.max(1_000L, mstockPollDelayMs);
        long multiplier = 1L << Math.min(failures - 1, 5);
        long delayMs = Math.min(MAX_TRANSIENT_BACKOFF_MS, baseDelay * multiplier);
        nextPollAttemptAt = Instant.now().plusMillis(delayMs);

        if (failures == 1 || failures % 5 == 0) {
            log.warn("MStock LTP polling transient failure (http {}). Backing off for {} ms; consecutiveFailures={}. Body={}",
                    failure.getHttpStatus(), delayMs, failures, summarize(failure.getResponseBody()));
        } else {
            log.debug("MStock LTP polling transient failure (http {}). Backing off for {} ms; consecutiveFailures={}",
                    failure.getHttpStatus(), delayMs, failures);
        }
    }

    private void resetTransientBackoffIfNeeded() {
        if (consecutiveTransientFailures > 0) {
            log.info("MStock LTP polling recovered after {} transient failure(s).", consecutiveTransientFailures);
            consecutiveTransientFailures = 0;
            nextPollAttemptAt = Instant.EPOCH;
        }
    }

    private String summarize(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240) + "...";
    }

    private Integer extractScripCode(String scripKey) {
        if (!StringUtils.hasText(scripKey)) return null;
        try {
            String codeStr = scripKey.replaceAll("[^0-9]", "");
            return Integer.parseInt(codeStr);
        } catch (Exception e) {
            return null;
        }
    }

    private String getMStockKey(Integer scripCode) {
        Optional<String> resolved = instrumentResolver.resolveInstrumentKey(scripCode);
        if (resolved.isPresent()) {
            String resolvedKey = resolved.get();
            String previous = scripCodeToMStockKeyCache.put(scripCode, resolvedKey);
            if (!resolvedKey.equals(previous)) {
                if (StringUtils.hasText(previous)) {
                    mStockKeyToScripCodeCache.remove(previous);
                }
                mStockKeyToScripCodeCache.put(resolvedKey, scripCode);
            } else if (!StringUtils.hasText(previous)) {
                mStockKeyToScripCodeCache.put(resolvedKey, scripCode);
            }
            if (shouldTraceSensexMapping(resolvedKey, scripCode)) {
                log.info("MStock key trace: scripCode={} resolvedKey={}", scripCode, resolvedKey);
            }
            return resolvedKey;
        }

        String previous = scripCodeToMStockKeyCache.remove(scripCode);
        if (StringUtils.hasText(previous)) {
            mStockKeyToScripCodeCache.remove(previous);
        }
        return null;
    }

    private boolean shouldTraceSensexMapping(String mstockKey, Integer scripCode) {
        if (mstockKey != null && mstockKey.toUpperCase().contains("SENSEX")) {
            return true;
        }
        return Integer.valueOf(999901).equals(scripCode);
    }
}
