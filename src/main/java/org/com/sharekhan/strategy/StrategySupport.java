package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockIntradayCandleService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.SpotSymbolAliases;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradeAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategySupport {

    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    public static final int CANDLE_MINUTES = 5;
    private static final int FNO_MINIMUM_EXPIRY_DAYS = 3;

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final List<DateTimeFormatter> EXPIRY_INPUT_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT)
    );

    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockInstrumentResolver mStockInstrumentResolver;
    private final MStockInstrumentRepository mStockInstrumentRepository;
    private final MStockIntradayCandleService mStockIntradayCandleService;
    private final SharekhanHistoricalService sharekhanHistoricalService;
    private final TradeExecutionService tradeExecutionService;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeSetupRepository;
    @Autowired(required = false)
    private TradeAuditService tradeAuditService;
    private final ConcurrentHashMap<FnoWarmupKey, FnoOptionContract> warmedFnoOptions = new ConcurrentHashMap<>();

    public StrategyApplyResponse waiting(StrategyMetadata metadata, String symbol, String message) {
        return StrategyApplyResponse.builder()
                .status("waiting")
                .message(message)
                .templateId(metadata.id())
                .symbol(symbol)
                .direction(metadata.optionType())
                .build();
    }

    public CandleLoad loadCandles(ScriptMasterEntity spotScript) {
        return loadCandles(spotScript, "5minute");
    }

    /** Loads MStock intraday candles at the requested interval. */
    public CandleLoad loadCandles(ScriptMasterEntity spotScript, String interval) {
        String effectiveInterval = StringUtils.hasText(interval) ? interval.trim() : "5minute";
        try {
            String symbol = spotScript != null ? spotScript.getTradingSymbol() : null;
            HardcodedMStockIndex hardcodedIndex = hardcodedMStockIndex(spotScript);
            if (hardcodedIndex != null) {
                return loadHardcodedIndexCandles(hardcodedIndex, symbol, effectiveInterval);
            }

            Optional<MStockPollInstrument> pollInstrumentOpt = resolveMStockPollInstrument(spotScript);
            if (pollInstrumentOpt.isEmpty()) {
                String reason = "Unable to resolve MStock instrument key for symbol=" + symbol
                        + ", exchange=" + (spotScript != null ? spotScript.getExchange() : null)
                        + ", scripCode=" + (spotScript != null ? spotScript.getScripCode() : null);
                log.warn(reason);
                printDiagnostic(reason);
                return new CandleLoad(List.of(), false, reason);
            }

            MStockPollInstrument pollInstrument = pollInstrumentOpt.get();
            String key = pollInstrument.key();
            MStockInstrumentEntity instrument = pollInstrument.instrument();
            String symbolToken = pollInstrument.token();
            if (!StringUtils.hasText(symbolToken)) {
                String reason = "MStock exchangeToken is missing for key=" + key
                        + ", instrumentToken=" + instrument.getInstrumentToken()
                        + ", tradingSymbol=" + instrument.getTradingSymbol()
                        + ", exchange=" + instrument.getExchange();
                log.warn(reason);
                printDiagnostic(reason);
                return new CandleLoad(List.of(), false, reason);
            }

            String exchange = pollInstrument.exchange();
            if (pollInstrument.bseFallback()) {
                String message = "Using BSE spot fallback for symbol=" + symbol + ", key=" + key
                        + ", token=" + symbolToken + " because no NSE MStock master row is available";
                log.info(message);
                printDiagnostic(message);
            }
            log.info("Loading MStock intraday candles for symbol={}, key={}, exchange={}, symbolToken={}, interval={}",
                    symbol, key, exchange, symbolToken, effectiveInterval);
            printDiagnostic("Loading candles symbol=" + symbol
                    + ", key=" + key
                    + ", exchange=" + exchange
                    + ", symbolToken=" + symbolToken
                    + ", interval=" + effectiveInterval);
            List<StrategyCandle> candles = mStockIntradayCandleService
                    .getIntradayCandles(exchange, symbolToken, effectiveInterval)
                    .stream()
                    .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                    .toList();
            if (!candles.isEmpty()) {
                log.info("Loaded {} MStock intraday candles for symbol={}, key={}, exchange={}, symbolToken={}",
                        candles.size(), symbol, key, exchange, symbolToken);
                printDiagnostic("Loaded " + candles.size()
                        + " candles for symbol=" + symbol
                        + ", key=" + key
                        + ", exchange=" + exchange
                        + ", symbolToken=" + symbolToken
                        + ", dates=" + summarizeCandleDates(candles));
                return new CandleLoad(candles, candles.stream().anyMatch(StrategyCandle::hasVolume), null);
            }

            String reason = "MStock intraday API returned zero valid candles for key=" + key
                    + ", exchange=" + exchange
                    + ", symbolToken=" + symbolToken
                    + ", interval=" + effectiveInterval;
            log.warn(reason);
            printDiagnostic(reason);
            return new CandleLoad(List.of(), false, reason);
        } catch (Exception ex) {
            String reason = "MStock intraday candles unavailable for "
                    + (spotScript != null ? spotScript.getTradingSymbol() : null)
                    + ": " + ex.getMessage();
            log.warn("{}. Strategy evaluation will wait for MStock 5-minute candles.", reason, ex);
            printDiagnostic(reason);
            return new CandleLoad(List.of(), false, reason);
        }
    }

    public CandleLoad loadCandlesWithHistoricalFallback(ScriptMasterEntity spotScript, int minimumCandles) {
        CandleLoad intradayLoad = loadCandles(spotScript);
        int required = Math.max(0, minimumCandles);
        if (required == 0 || intradayLoad.candles().size() >= required) {
            return intradayLoad;
        }

        List<StrategyCandle> historical = loadHistoricalCandles(spotScript);
        if (historical.isEmpty()) {
            return intradayLoad;
        }

        List<StrategyCandle> merged = mergeByTimestamp(historical, intradayLoad.candles(), required);
        if (merged.isEmpty()) {
            return intradayLoad;
        }

        printDiagnostic("Combined candles using Sharekhan historical fallback: intraday=" + intradayLoad.candles().size()
                + ", historical=" + historical.size()
                + ", merged=" + merged.size()
                + ", required=" + required
                + ", symbol=" + (spotScript != null ? spotScript.getTradingSymbol() : null));

        return new CandleLoad(merged, intradayLoad.hasVolume(), intradayLoad.reason());
    }

    public TriggerTradeRequestEntity executeTriggeredTrade(TriggerRequest trigger) {
        return tradeExecutionService.executeTriggeredTrade(trigger);
    }

    /** Creates a pending request only; price-trigger evaluation decides when to submit its entry order. */
    public TriggerTradeRequestEntity createPendingTradeRequest(TriggerRequest trigger) {
        return tradeExecutionService.executeTrade(trigger);
    }

    public void auditStrategy(StrategyApplyRequest request, StrategyMetadata metadata, String symbol,
                              String eventType, String outcome, String reason,
                              Fno925EntryQualificationService.Signal signal, TriggerRequest trigger) {
        if (tradeAuditService == null) return;
        tradeAuditService.record(TradeAuditEventEntity.builder()
                .appUserId(request != null ? request.getUserId() : null)
                .strategyId(metadata != null ? metadata.id() : null)
                .source(request != null ? request.getSource() : null)
                .symbol(symbol).eventType(eventType).outcome(outcome).reason(reason)
                .optionType(trigger != null ? trigger.getOptionType() : metadata != null ? metadata.optionType() : null)
                .expiry(trigger != null ? trigger.getExpiry() : null)
                .strikePrice(trigger != null ? trigger.getStrikePrice() : null)
                .spotPrice(signal != null ? signal.entryPrice() : null)
                .details(signal == null ? null : "setup=" + signal.setup() + ", entry=" + signal.entryPrice()
                        + ", stop=" + signal.stopLoss())
                .build());
    }

    public void auditTradeRequest(StrategyApplyRequest request, StrategyMetadata metadata, String symbol,
                                  String outcome, TriggerRequest trigger, TriggerTradeRequestEntity tradeRequest) {
        if (tradeAuditService == null) return;
        tradeAuditService.record(TradeAuditEventEntity.builder()
                .appUserId(request != null ? request.getUserId() : null)
                .triggerRequestId(tradeRequest != null ? tradeRequest.getId() : null)
                .strategyId(metadata != null ? metadata.id() : null)
                .source(trigger != null ? trigger.getSource() : request != null ? request.getSource() : null)
                .symbol(symbol).eventType("TRADE_REQUEST").outcome(outcome)
                .optionType(trigger != null ? trigger.getOptionType() : null)
                .expiry(trigger != null ? trigger.getExpiry() : null)
                .strikePrice(trigger != null ? trigger.getStrikePrice() : null)
                .details("entry=" + (trigger != null ? trigger.getEntryPrice() : null)
                        + ", stop=" + (trigger != null ? trigger.getStopLoss() : null))
                .build());
    }

    public void warmUpAtmOptionLtp(StrategyApplyRequest request,
                                   StrategyMetadata metadata,
                                   String symbol,
                                   double referencePrice) {
        try {
            String expiry = nearestExpiry(symbol, metadata.optionType());
            double strike = nearestStrike(symbol, metadata.optionType(), expiry, referencePrice);

            TriggerRequest warmup = new TriggerRequest();
            warmup.setInstrument(symbol);
            warmup.setStrikePrice(strike);
            warmup.setOptionType(metadata.optionType());
            warmup.setExpiry(expiry);
            warmup.setUserId(request.getUserId());
            warmup.setBrokerCredentialsId(request.getBrokerCredentialsId());
            warmup.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + metadata.id());

            tradeExecutionService.warmUpOptionLtp(warmup, "Strategy range complete");
        } catch (Exception e) {
            log.warn("Unable to warm ATM option LTP for strategy template={} symbol={} direction={}: {}",
                    metadata.id(), symbol, metadata.optionType(), e.getMessage());
            log.debug("Strategy ATM option warmup failed", e);
        }
    }

    /**
     * Prepares the exact spot and preferred-expiry ATM option feeds for the
     * manually configured F&O strategies before a breakout is eligible.
     */
    public void warmUpPreferredFnoFeeds(StrategyApplyRequest request,
                                         StrategyMetadata metadata,
                                         String symbol,
                                         ScriptMasterEntity spotScript) {
        tradeExecutionService.warmUpSpotLtp(spotScript, "F&O strategy monitoring");
        try {
            CandleLoad candleLoad = loadCandles(spotScript);
            StrategyCandle latest = candleLoad.candles().stream()
                    .max(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                    .orElseThrow(() -> new IllegalStateException("no current spot candle is available"));
            double referencePrice = latest.close();
            if (!Double.isFinite(referencePrice) || referencePrice <= 0d) {
                throw new IllegalStateException("current spot price is invalid");
            }

            String expiry = preferredFnoExpiry(symbol, metadata.optionType());
            double strike = nearestStrike(symbol, metadata.optionType(), expiry, referencePrice);
            TriggerRequest warmup = new TriggerRequest();
            warmup.setInstrument(symbol);
            warmup.setStrikePrice(strike);
            warmup.setOptionType(metadata.optionType());
            warmup.setExpiry(expiry);
            warmup.setUserId(request.getUserId());
            warmup.setBrokerCredentialsId(request.getBrokerCredentialsId());
            warmup.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + metadata.id());

            if (tradeExecutionService.warmUpOptionLtp(warmup, "F&O strategy monitoring").isPresent()) {
                warmedFnoOptions.put(fnoWarmupKey(request, metadata, symbol), new FnoOptionContract(expiry, strike));
            }
        } catch (Exception e) {
            log.warn("Unable to warm preferred F&O option feed for strategy template={} symbol={} direction={}: {}",
                    metadata.id(), symbol, metadata.optionType(), e.getMessage());
            log.debug("Preferred F&O option warm-up failed", e);
        }
    }

    /**
     * If spot movement has changed the calculated ATM strike, retain the
     * pre-warmed contract when the new ATM contract has no executable book.
     */
    public FnoOptionContract resolveFnoEntryContract(StrategyApplyRequest request,
                                                      StrategyMetadata metadata,
                                                      String symbol,
                                                      String currentExpiry,
                                                      double currentStrike) {
        FnoOptionContract current = new FnoOptionContract(currentExpiry, currentStrike);
        FnoOptionContract warmed = warmedFnoOptions.get(fnoWarmupKey(request, metadata, symbol));
        if (warmed == null || warmed.matches(current)) {
            return current;
        }
        TriggerRequest candidate = optionRequest(symbol, metadata.optionType(), current);
        if (tradeExecutionService.hasFreshOptionBook(candidate)) {
            return current;
        }
        log.warn("FNO_PREWARMED_STRIKE_FALLBACK | strategy={} symbol={} currentExpiry={} currentStrike={} "
                        + "warmedExpiry={} warmedStrike={} reason=NEW_ATM_BOOK_UNAVAILABLE",
                metadata.id(), symbol, current.expiry(), current.strike(), warmed.expiry(), warmed.strike());
        return warmed;
    }

    private FnoWarmupKey fnoWarmupKey(StrategyApplyRequest request, StrategyMetadata metadata, String symbol) {
        return new FnoWarmupKey(LocalDate.now(MARKET_ZONE),
                request != null ? request.getUserId() : null,
                request != null ? request.getBrokerCredentialsId() : null,
                metadata != null ? metadata.id() : null,
                symbol != null ? symbol.trim().toUpperCase(Locale.ROOT) : null);
    }

    private TriggerRequest optionRequest(String symbol, String optionType, FnoOptionContract contract) {
        TriggerRequest request = new TriggerRequest();
        request.setInstrument(symbol);
        request.setOptionType(optionType);
        request.setExpiry(contract.expiry());
        request.setStrikePrice(contract.strike());
        return request;
    }

    public record FnoOptionContract(String expiry, double strike) {
        boolean matches(FnoOptionContract other) {
            return other != null && Objects.equals(expiry, other.expiry)
                    && Double.compare(strike, other.strike) == 0;
        }
    }

    private record FnoWarmupKey(LocalDate day, Long userId, Long brokerCredentialsId,
                                String strategyId, String symbol) { }

    public TriggerTradeRequestEntity findExisting(TriggerRequest trigger) {
        if (trigger.getUserId() == null) {
            return null;
        }
        List<TriggerTradeRequestEntity> matches = triggerTradeRequestRepository
                .findBySymbolAndStrikePriceAndOptionTypeAndAppUserIdAndStatus(
                        trigger.getInstrument(),
                        trigger.getStrikePrice(),
                        trigger.getOptionType(),
                        trigger.getUserId(),
                        TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        return matches == null || matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * The prior-day ATR setup is directional, rather than strike-specific.  Its
     * qualifying entry/ATM strike can change between two Apply requests, so a
     * strike-based duplicate lookup would create a second live setup for the
     * same symbol.  CE and PE remain independent strategies.
     */
    public TriggerTradeRequestEntity findActiveAtrPreviousDaySetup(TriggerRequest trigger) {
        return findActiveSetup(trigger, AbstractAtrPreviousDayFnoStrategy.SOURCE);
    }

    /** Finds an active directional setup for a symbol created by one strategy source. */
    public TriggerTradeRequestEntity findActiveSetup(TriggerRequest trigger, String source) {
        if (trigger == null || trigger.getUserId() == null || !StringUtils.hasText(trigger.getInstrument())
                || !StringUtils.hasText(trigger.getOptionType()) || !StringUtils.hasText(source)) {
            return null;
        }
        List<TriggerTradeRequestEntity> matches = triggerTradeRequestRepository
                .findBySymbolAndAppUserIdAndStatusIn(trigger.getInstrument(), trigger.getUserId(), List.of(
                        TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
                        TriggeredTradeStatus.ENTRY_SUBMITTING,
                        TriggeredTradeStatus.TRIGGERED));
        return matches == null ? null : matches.stream()
                .filter(item -> source.equalsIgnoreCase(item.getSource()))
                .filter(item -> trigger.getOptionType().equalsIgnoreCase(item.getOptionType()))
                .findFirst()
                .orElse(null);
    }

    /** A prior-day ATR symbol may enter only once in an IST trading day, even after its request has exited. */
    public boolean hasAtrPreviousDayEntryOn(LocalDate day, Long appUserId, String symbol) {
        return hasEntryForSymbolOn(AbstractAtrPreviousDayFnoStrategy.SOURCE, day, appUserId, symbol);
    }

    public List<TriggeredTradeSetupEntity> atrPreviousDayEntriesOn(LocalDate day, Long appUserId,
                                                                     String symbol, String optionType) {
        if (day == null || appUserId == null || !StringUtils.hasText(symbol) || !StringUtils.hasText(optionType)
                || triggeredTradeSetupRepository == null) {
            return List.of();
        }
        LocalDateTime start = day.atStartOfDay();
        return triggeredTradeSetupRepository.findTriggeredForSymbolOptionTypeOnDay(
                AbstractAtrPreviousDayFnoStrategy.SOURCE, symbol.trim(), optionType.trim(), appUserId,
                start, start.plusDays(1));
    }

    /** Returns whether this strategy source has already entered the underlying for this user on the IST day. */
    public boolean hasEntryForSymbolOn(String source, LocalDate day, Long appUserId, String symbol) {
        if (day == null || appUserId == null || !StringUtils.hasText(symbol) || triggeredTradeSetupRepository == null) {
            return false;
        }
        if (!StringUtils.hasText(source)) {
            return false;
        }
        LocalDateTime start = day.atStartOfDay();
        return triggeredTradeSetupRepository.countTriggeredForSymbolOnDay(
                source.trim(), symbol.trim(), appUserId, start, start.plusDays(1)) > 0;
    }

    public ScriptMasterEntity resolveSpotScript(String symbol) {
        for (String candidate : SpotSymbolAliases.candidates(symbol)) {
            ScriptMasterEntity script = findSpotScript(candidate, "NC");
            if (script != null) {
                return script;
            }
            script = findSpotScript(candidate, "BC");
            if (script != null) {
                return script;
            }
        }
        throw new IllegalArgumentException("Spot script not found for symbol " + symbol);
    }

    /** Returns a user-safe reason when a spot instrument cannot be polled from MStock. */
    public Optional<String> mstockAvailabilityFailure(ScriptMasterEntity spotScript) {
        if (spotScript == null) {
            return Optional.of("spot script is unavailable");
        }
        if (hardcodedMStockIndex(spotScript) != null) {
            return Optional.empty();
        }
        try {
            Optional<MStockPollInstrument> pollInstrument = resolveMStockPollInstrument(spotScript);
            if (pollInstrument.isEmpty()) {
                return Optional.of("MStock instrument master row is unavailable");
            }
            if (!StringUtils.hasText(pollInstrument.get().token())) {
                return Optional.of("MStock exchange token is unavailable");
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of("MStock instrument lookup failed: " + e.getMessage());
        }
    }

    /**
     * Resolves the exact market identity used for strategy candles.  Historical
     * candles must use this same exchange/token; mixing NSE historical data with
     * BSE intraday data (or the reverse) produces invalid prior-day levels.
     */
    public Optional<MStockHistoricalIdentity> resolveMStockHistoricalIdentity(ScriptMasterEntity spotScript) {
        return resolveMStockPollInstrument(spotScript).flatMap(instrument -> {
            try {
                long token = Long.parseLong(instrument.token());
                return token > 0L
                        ? Optional.of(new MStockHistoricalIdentity(instrument.exchange(), token, instrument.key()))
                        : Optional.empty();
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        });
    }

    public String nearestExpiry(String symbol, String optionType) {
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        LocalTime expiryCutoff = LocalTime.of(15, 30);
        return scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol, optionType)
                .stream()
                .filter(StringUtils::hasText)
                .map(this::parseExpiry)
                .filter(Objects::nonNull)
                .filter(expiry -> expiry.isAfter(now.toLocalDate())
                        || (expiry.isEqual(now.toLocalDate()) && now.toLocalTime().isBefore(expiryCutoff)))
                .min(Comparator.naturalOrder())
                .map(EXPIRY_FORMAT::format)
                .orElseThrow(() -> new IllegalArgumentException("No valid option expiry found for " + symbol + " " + optionType));
    }

    /**
     * F&O 09:25 strategies avoid near-expiry contracts. An expiry that is at
     * most three calendar days away is skipped in favour of the next available
     * expiry, which avoids taking a new position into expiry-week decay.
     */
    public String preferredFnoExpiry(String symbol, String optionType) {
        return preferredFnoExpiry(symbol, optionType, LocalDate.now(MARKET_ZONE));
    }

    String preferredFnoExpiry(String symbol, String optionType, LocalDate tradeDate) {
        LocalDate minimumExpiryDate = tradeDate.plusDays(FNO_MINIMUM_EXPIRY_DAYS);
        return scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol, optionType)
                .stream()
                .filter(StringUtils::hasText)
                .map(this::parseExpiry)
                .filter(Objects::nonNull)
                .filter(expiry -> expiry.isAfter(minimumExpiryDate))
                .min(Comparator.naturalOrder())
                .map(EXPIRY_FORMAT::format)
                .orElseThrow(() -> new IllegalArgumentException("No F&O option expiry more than "
                        + FNO_MINIMUM_EXPIRY_DAYS + " days away found for " + symbol + " " + optionType));
    }

    public double nearestStrike(String symbol, String optionType, String expiry, double referencePrice) {
        return scriptMasterRepository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(symbol, optionType, expiry)
                .stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(strike -> Math.abs(strike - referencePrice)))
                .orElseThrow(() -> new IllegalArgumentException("No strike found for " + symbol + " " + optionType + " " + expiry));
    }

    public double roundPrice(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    public Double roundNullable(Double value) {
        return value == null || !Double.isFinite(value) ? null : roundPrice(value);
    }

    public String normalizeSymbolKey(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public String summarizeCandleDates(List<StrategyCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return "none";
        }
        return candles.stream()
                .map(StrategyCandle::date)
                .distinct()
                .sorted()
                .map(LocalDate::toString)
                .toList()
                .toString();
    }

    public String summarizeCandleTimes(List<StrategyCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return "none";
        }
        return candles.stream()
                .map(c -> c.time().toString())
                .limit(20)
                .toList()
                .toString();
    }

    private CandleLoad loadHardcodedIndexCandles(HardcodedMStockIndex index, String symbol, String interval) {
        String key = index.exchange() + ":" + index.script();
        printDiagnostic("Loading hardcoded index candles symbol=" + symbol
                + ", key=" + key
                + ", exchange=" + index.exchange()
                + ", symbolToken=" + index.exchangeToken()
                + ", name=" + index.name()
                + ", interval=" + interval);
        List<StrategyCandle> candles = mStockIntradayCandleService
                .getIntradayCandles(index.exchange(), index.exchangeToken(), interval)
                .stream()
                .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
                .toList();
        if (!candles.isEmpty()) {
            printDiagnostic("Loaded " + candles.size()
                    + " hardcoded index candles for symbol=" + symbol
                    + ", key=" + key
                    + ", exchange=" + index.exchange()
                    + ", symbolToken=" + index.exchangeToken()
                    + ", dates=" + summarizeCandleDates(candles));
            return new CandleLoad(candles, candles.stream().anyMatch(StrategyCandle::hasVolume), null);
        }

        String reason = "MStock intraday API returned zero valid candles for hardcoded index key=" + key
                + ", exchange=" + index.exchange()
                + ", symbolToken=" + index.exchangeToken()
                + ", interval=" + interval;
        log.warn(reason);
        printDiagnostic(reason);
        return new CandleLoad(List.of(), false, reason);
    }

    private HardcodedMStockIndex hardcodedMStockIndex(ScriptMasterEntity spotScript) {
        if (spotScript == null) {
            return null;
        }
        String symbol = normalizeSymbolKey(spotScript.getTradingSymbol());
        Integer scripCode = spotScript.getScripCode();

        if ("NIFTY".equals(symbol) || "NIFTY50".equals(symbol) || Integer.valueOf(20000).equals(scripCode)) {
            return new HardcodedMStockIndex("NIFTY50", "26000", "Nifty 50", "NSE");
        }
        if ("BANKNIFTY".equals(symbol) || "NIFTYBANK".equals(symbol) || Integer.valueOf(26009).equals(scripCode)) {
            return new HardcodedMStockIndex("NIFTYBANK", "26009", "Nifty Bank", "NSE");
        }
        if ("SENSEX".equals(symbol) || Integer.valueOf(51).equals(scripCode)) {
            return new HardcodedMStockIndex("SENSEX", "51", "SENSEX", "BSE");
        }
        return null;
    }

    private ScriptMasterEntity findSpotScript(String symbol, String exchange) {
        return scriptMasterRepository.findByExchangeIgnoreCase(exchange).stream()
                .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(symbol))
                .filter(s -> s.getStrikePrice() == null || Math.abs(s.getStrikePrice()) < 0.001d)
                .filter(s -> !StringUtils.hasText(s.getExpiry()))
                .findFirst()
                .orElse(null);
    }

    private LocalDate parseExpiry(String raw) {
        String trimmed = raw != null ? raw.trim() : null;
        if (!StringUtils.hasText(trimmed)) {
            return null;
        }
        for (DateTimeFormatter formatter : EXPIRY_INPUT_FORMATS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String normalizeMStockExchange(String exchange) {
        if ("NC".equalsIgnoreCase(exchange)) return "NSE";
        if ("BC".equalsIgnoreCase(exchange)) return "BSE";
        if ("NF".equalsIgnoreCase(exchange)) return "NFO";
        if ("BF".equalsIgnoreCase(exchange)) return "BFO";
        return exchange;
    }

    /**
     * Resolves the normal MStock master row first.  A few NSE cash symbols are absent from
     * the MStock master although their BSE equity row is present (for example KOTAKBANK-A).
     * For strategy candle polling only, use that BSE row as a last resort instead of leaving
     * the configured symbol permanently unavailable.
     */
    private Optional<MStockPollInstrument> resolveMStockPollInstrument(ScriptMasterEntity spotScript) {
        Optional<String> keyOpt = mStockInstrumentResolver.resolveInstrumentKey(spotScript);
        if (keyOpt.isPresent()) {
            Optional<MStockInstrumentEntity> exact = mStockInstrumentRepository.findByInstrumentKey(keyOpt.get());
            if (exact.isPresent()) {
                MStockInstrumentEntity instrument = exact.get();
                return Optional.of(new MStockPollInstrument(
                        keyOpt.get(),
                        instrument,
                        keyExchange(keyOpt.get(), spotScript),
                        trimToNull(instrument.getExchangeToken()),
                        false));
            }
        }

        // F&O entries are traded against the NSE underlying.  A BSE fallback can
        // be useful for a display-only chart, but must never define PDH/PDL,
        // ATR, or a live NSE option trigger: the two exchanges can close at
        // different prices.  Use the NSE cash token directly when the MStock
        // master is incomplete; if MStock cannot serve it, leave the strategy
        // waiting instead of silently calculating from BSE candles.
        Optional<MStockPollInstrument> directNse = resolveDirectNseSpotInstrument(spotScript, keyOpt.orElse(null));
        if (directNse.isPresent()) {
            return directNse;
        }

        return resolveBseSpotFallback(spotScript);
    }

    private Optional<MStockPollInstrument> resolveDirectNseSpotInstrument(ScriptMasterEntity spotScript, String resolvedKey) {
        if (spotScript == null || spotScript.getScripCode() == null || spotScript.getScripCode() <= 0
                || !("NC".equalsIgnoreCase(spotScript.getExchange()) || "NSE".equalsIgnoreCase(spotScript.getExchange()))) {
            return Optional.empty();
        }
        String symbol = normalizeSymbolKey(spotScript.getTradingSymbol());
        if (!StringUtils.hasText(symbol)) {
            return Optional.empty();
        }
        String key = StringUtils.hasText(resolvedKey) ? resolvedKey : "NSE:" + symbol + "-EQ";
        MStockInstrumentEntity instrument = MStockInstrumentEntity.builder()
                .exchange("NSE")
                .instrumentKey(key)
                .tradingSymbol(symbol + "-EQ")
                .instrumentToken(spotScript.getScripCode().longValue())
                .exchangeToken(String.valueOf(spotScript.getScripCode()))
                .build();
        log.warn("MStock NSE master row is missing for symbol={}; using direct NSE spot token={} and refusing BSE strategy candles",
                symbol, spotScript.getScripCode());
        return Optional.of(new MStockPollInstrument(key, instrument, "NSE",
                String.valueOf(spotScript.getScripCode()), false));
    }

    private Optional<MStockPollInstrument> resolveBseSpotFallback(ScriptMasterEntity spotScript) {
        if (spotScript == null || !"NC".equalsIgnoreCase(spotScript.getExchange())) {
            return Optional.empty();
        }

        for (String candidate : SpotSymbolAliases.candidates(spotScript.getTradingSymbol())) {
            String normalized = normalizeSymbolKey(candidate);
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            Optional<MStockInstrumentEntity> match = mStockInstrumentRepository
                    .findByExchangeAndTradingSymbolPattern("BSE", normalized + "%")
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(this::isBseEquity)
                    .filter(instrument -> isBseSymbolForCandidate(instrument.getTradingSymbol(), normalized))
                    .filter(instrument -> StringUtils.hasText(bsePollingToken(instrument)))
                    .min(Comparator.comparing(instrument -> instrument.getTradingSymbol().toUpperCase(Locale.ROOT)));
            if (match.isPresent()) {
                MStockInstrumentEntity instrument = match.get();
                String key = StringUtils.hasText(instrument.getInstrumentKey())
                        ? instrument.getInstrumentKey().trim()
                        : "BSE:" + instrument.getTradingSymbol().trim();
                return Optional.of(new MStockPollInstrument(key, instrument, "BSE", bsePollingToken(instrument), true));
            }
        }
        return Optional.empty();
    }

    private boolean isBseEquity(MStockInstrumentEntity instrument) {
        return "BSE".equalsIgnoreCase(instrument.getExchange())
                && "EQUITY".equalsIgnoreCase(instrument.getInstrumentType());
    }

    private boolean isBseSymbolForCandidate(String tradingSymbol, String normalizedCandidate) {
        if (!StringUtils.hasText(tradingSymbol) || !StringUtils.hasText(normalizedCandidate)) {
            return false;
        }
        String normalizedSymbol = tradingSymbol.trim().toUpperCase(Locale.ROOT);
        return normalizedSymbol.equals(normalizedCandidate) || normalizedSymbol.startsWith(normalizedCandidate + "-");
    }

    private String bsePollingToken(MStockInstrumentEntity instrument) {
        String exchangeToken = trimToNull(instrument.getExchangeToken());
        if (exchangeToken != null) {
            return exchangeToken;
        }
        return instrument.getInstrumentToken() != null ? String.valueOf(instrument.getInstrumentToken()) : null;
    }

    private String keyExchange(String key, ScriptMasterEntity spotScript) {
        return key.contains(":") ? key.substring(0, key.indexOf(':')) : normalizeMStockExchange(spotScript.getExchange());
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void printDiagnostic(String message) {
        System.out.println("[MSTOCK-STRATEGY] " + message);
    }

    private List<StrategyCandle> loadHistoricalCandles(ScriptMasterEntity spotScript) {
        if (spotScript == null || spotScript.getScripCode() == null) {
            return List.of();
        }
        LocalDate today = LocalDate.now(MARKET_ZONE);
        LocalDate from = today.minusDays(10);
        List<SharekhanHistoricalService.HistoricalCandle> historical = sharekhanHistoricalService
                .getHistoricalCandles(spotScript.getScripCode(), "5minute", from, today);

        if (historical.isEmpty()) {
            return List.of();
        }

        return historical.stream()
                .filter(c -> c.date() != null && c.time() != null)
                .map(c -> new StrategyCandle(c.date(), c.time(), c.open(), c.high(), c.low(), c.close(), null))
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                .toList();
    }

    private List<StrategyCandle> mergeByTimestamp(List<StrategyCandle> historical,
                                                  List<StrategyCandle> intraday,
                                                  int maxCandles) {
        Map<LocalDateTime, StrategyCandle> mergedMap = new TreeMap<>();

        for (StrategyCandle candle : historical) {
            LocalDateTime key = candleKey(candle);
            if (key != null) {
                mergedMap.put(key, candle);
            }
        }
        for (StrategyCandle candle : intraday) {
            LocalDateTime key = candleKey(candle);
            if (key != null) {
                mergedMap.put(key, candle);
            }
        }

        List<StrategyCandle> merged = new ArrayList<>(mergedMap.values());
        if (maxCandles > 0 && merged.size() > maxCandles) {
            return merged.subList(merged.size() - maxCandles, merged.size());
        }
        return merged;
    }

    private LocalDateTime candleKey(StrategyCandle candle) {
        if (candle == null || candle.date() == null || candle.time() == null) {
            return null;
        }
        return LocalDateTime.of(candle.date(), candle.time());
    }

    private record HardcodedMStockIndex(String script, String exchangeToken, String name, String exchange) {
    }

    private record MStockPollInstrument(String key,
                                        MStockInstrumentEntity instrument,
                                        String exchange,
                                        String token,
                                        boolean bseFallback) {
    }

    public record MStockHistoricalIdentity(String exchange, long instrumentToken, String instrumentKey) { }
}
