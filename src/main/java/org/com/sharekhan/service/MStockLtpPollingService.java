package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Autowired(required = false)
    private ShoonyaQuoteService shoonyaQuoteService;
    @Autowired(required = false)
    private ScriptMasterRepository scriptMasterRepository;

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    // Keep quote polling through the post-close buffer, then stop all market-data refreshes.
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 45);
    private static final long MAX_TRANSIENT_BACKOFF_MS = 60_000L;

    private final Map<Integer, String> scripCodeToMStockKeyCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> mStockKeyToScripCodeCache = new ConcurrentHashMap<>();
    private final AtomicBoolean afterHoursLogged = new AtomicBoolean(false);
    private final AtomicBoolean missingTokenLogged = new AtomicBoolean(false);
    private final Map<Integer, Instant> shoonyaLastRefreshAt = new ConcurrentHashMap<>();
    private final AtomicInteger shoonyaPollCursor = new AtomicInteger();
    private volatile Instant nextPollAttemptAt = Instant.EPOCH;
    private volatile int consecutiveTransientFailures = 0;

    @Value("${app.mstock.poll-delay-ms:1500}")
    private long mstockPollDelayMs;

    @Value("${app.market-data.sharekhan-quote-stale-ms:2000}")
    private long sharekhanQuoteStaleMs;

    @Value("${app.shoonya.poll-max-active-scrips:20}")
    private int shoonyaPollMaxActiveScrips;

    @Value("${app.shoonya.poll-min-interval-ms:5000}")
    private long shoonyaPollMinIntervalMs;

    @Scheduled(fixedDelayString = "${app.shoonya.poll-delay-ms:1500}")
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

            refreshActiveQuotesFromShoonya(activeScripKeys);
        } catch (Exception e) {
            log.warn("Error during Shoonya LTP polling: {}", e.getMessage());
            log.debug("Shoonya LTP polling error trace", e);
        }
    }

    private boolean hasMStockToken() {
        return tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK) != null
                || StringUtils.hasText(tokenStoreService.getFirstNonExpiredTokenForBroker(Broker.MSTOCK))
                || StringUtils.hasText(tokenStoreService.getAccessToken(Broker.MSTOCK));
    }

    private Set<Integer> refreshActiveQuotesFromShoonya(Set<String> activeScripKeys) {
        if (shoonyaQuoteService == null || scriptMasterRepository == null || shoonyaPollMaxActiveScrips <= 0) {
            return Set.of();
        }
        Set<Integer> refreshed = new java.util.HashSet<>();
        List<ScriptMasterEntity> activeScripts = new ArrayList<>();
        for (String scripKey : activeScripKeys) {
            Integer scripCode = extractScripCode(scripKey);
            if (scripCode == null) {
                continue;
            }
            ScriptMasterEntity script = scriptMasterRepository.findByScripCode(scripCode);
            if (script != null) {
                activeScripts.add(script);
            }
        }
        activeScripts.sort(Comparator.comparing(ScriptMasterEntity::getScripCode));
        if (activeScripts.isEmpty()) {
            return refreshed;
        }

        Instant now = Instant.now();
        int attempted = 0;
        int start = Math.floorMod(shoonyaPollCursor.getAndAdd(shoonyaPollMaxActiveScrips), activeScripts.size());
        for (int offset = 0; offset < activeScripts.size() && attempted < shoonyaPollMaxActiveScrips; offset++) {
            ScriptMasterEntity script = activeScripts.get((start + offset) % activeScripts.size());
            Integer scripCode = script.getScripCode();
            if (scripCode == null) {
                continue;
            }
            Instant lastRefresh = shoonyaLastRefreshAt.get(scripCode);
            if (lastRefresh != null && lastRefresh.plusMillis(configuredShoonyaPollMinIntervalMs()).isAfter(now)) {
                continue;
            }
            attempted++;
            try {
                Optional<ShoonyaQuoteService.LiveQuote> quoteOpt = shoonyaQuoteService.getQuote(script);
                if (quoteOpt.isEmpty()) {
                    continue;
                }
                ShoonyaQuoteService.LiveQuote quote = quoteOpt.get();
                // Shoonya can occasionally return a quote for a different instrument than the
                // one requested (for example, an NSE equity quote for an NFO option token).
                // Never put that value under the requested scrip code: consumers such as the
                // intraday closer would otherwise treat a spot price as the option LTP.
                if (!quote.hasConfirmedIdentity()) {
                    log.warn("SHOONYA_QUOTE_IDENTITY_MISMATCH | requestedScrip={} | requestedSymbol={} | requestedToken={} | returnedSymbol={} | returnedToken={} | ltp={}; rejecting cache update",
                            scripCode,
                            quote.tradingSymbol(),
                            quote.token(),
                            quote.returnedTradingSymbol(),
                            quote.returnedToken(),
                            quote.referencePrice());
                    continue;
                }
                Double price = quote.referencePrice();
                if (!isUsablePrice(price)) {
                    continue;
                }
                ltpCacheService.updateLtp(scripCode, price);
                quoteCacheService.recordQuote(scripCode, quote.bestBid(), quote.bestAsk(), quote.lastPrice());
                shoonyaLastRefreshAt.put(scripCode, now);
                refreshed.add(scripCode);
                scripExecutorManager.submitTriggerTask(scripCode,
                        () -> priceTriggerService.evaluatePriceTrigger(scripCode, price));
                scripExecutorManager.submitMonitorTask(scripCode,
                        () -> priceTriggerService.monitorOpenTrades(scripCode, price));
            } catch (Exception e) {
                log.debug("Shoonya quote refresh failed for scrip {}: {}", scripCode, e.getMessage());
            }
        }
        return refreshed;
    }

    private long configuredShoonyaPollMinIntervalMs() {
        return shoonyaPollMinIntervalMs > 0 ? shoonyaPollMinIntervalMs : 5000L;
    }

    private boolean isFnoOption(ScriptMasterEntity script) {
        if (script == null || !StringUtils.hasText(script.getOptionType())) {
            return false;
        }
        String exchange = script.getExchange() == null ? "" : script.getExchange().trim();
        return "NF".equalsIgnoreCase(exchange) || "NFO".equalsIgnoreCase(exchange)
                || "BF".equalsIgnoreCase(exchange) || "BFO".equalsIgnoreCase(exchange);
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
