package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.StrategyTemplateResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.springframework.stereotype.Service;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyTemplateService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OR_START = LocalTime.of(9, 15);
    private static final LocalTime OR_END = LocalTime.of(9, 30);
    private static final int CANDLE_MINUTES = 5;
    private static final int AVERAGE_VOLUME_CANDLES = 20;
    private static final double VOLUME_MULTIPLIER = 1.5d;
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final List<DateTimeFormatter> EXPIRY_INPUT_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT)
    );

    private static final Map<String, StrategyDefinition> DEFINITIONS = Map.of(
            "ORB_915_930_CE", new StrategyDefinition(
                    "ORB_915_930_CE",
                    "ORB 9:15-9:30 CE",
                    "First 15-minute range breakout above ORH with volume and VWAP filters.",
                    "CE"),
            "ORB_915_930_PE", new StrategyDefinition(
                    "ORB_915_930_PE",
                    "ORB 9:15-9:30 PE",
                    "First 15-minute range breakdown below ORL with volume and VWAP filters.",
                    "PE")
    );

    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockInstrumentResolver mStockInstrumentResolver;
    private final MStockInstrumentRepository mStockInstrumentRepository;
    private final MStockIntradayCandleService mStockIntradayCandleService;
    private final TradeExecutionService tradeExecutionService;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;

    public List<StrategyTemplateResponse> listTemplates() {
        return DEFINITIONS.values().stream()
                .sorted(Comparator.comparing(StrategyDefinition::id))
                .map(def -> StrategyTemplateResponse.builder()
                        .id(def.id())
                        .name(def.name())
                        .description(def.description())
                        .build())
                .toList();
    }

    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        validate(request);
        StrategyDefinition definition = DEFINITIONS.get(normalizeTemplateId(request.getTemplateId()));
        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);

        ScriptMasterEntity spotScript = resolveSpotScript(symbol);
        LocalDate today = LocalDate.now(MARKET_ZONE);
        LocalDateTime now = LocalDateTime.now(MARKET_ZONE);
        if (now.toLocalTime().isBefore(OR_END)) {
            return waiting(definition, symbol, "Waiting for opening range 9:15-9:30 to complete.");
        }

        CandleLoad candleLoad = loadCandles(spotScript);
        List<StrategyCandle> candles = candleLoad.candles().stream()
                .filter(c -> today.equals(c.date()))
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                .toList();
        if (candles.isEmpty()) {
            String reason = candleLoad.reason();
            if (!StringUtils.hasText(reason) && !candleLoad.candles().isEmpty()) {
                reason = "Loaded " + candleLoad.candles().size()
                        + " candles from MStock, but none matched today's market date "
                        + today
                        + ". Candle dates: "
                        + summarizeCandleDates(candleLoad.candles());
                log.warn(reason);
                printDiagnostic(reason);
            }
            String detail = StringUtils.hasText(reason) ? " Reason: " + reason : "";
            return waiting(definition, symbol, "No intraday candles available for " + symbol + "." + detail);
        }

        List<StrategyCandle> openingRange = candles.stream()
                .filter(c -> !c.time().isBefore(OR_START) && c.time().isBefore(OR_END))
                .toList();
        if (openingRange.size() < 3) {
            String reason = "Opening range 9:15-9:30 is not complete. Found "
                    + openingRange.size()
                    + " candles in range. Available candle times: "
                    + summarizeCandleTimes(candles);
            log.warn(reason);
            printDiagnostic(reason);
            return waiting(definition, symbol, reason);
        }

        double orh = openingRange.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double orl = openingRange.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        warmUpAtmOptionLtp(request, definition, symbol, orh, orl);

        if (now.toLocalTime().isBefore(OR_END.plusMinutes(CANDLE_MINUTES))) {
            return StrategyApplyResponse.builder()
                    .status("waiting")
                    .message("Opening range complete; ATM option LTP subscription warmed. Waiting for first completed 5-minute candle after 9:30.")
                    .templateId(definition.id())
                    .symbol(symbol)
                    .direction(definition.optionType())
                    .openingRangeHigh(roundPrice(orh))
                    .openingRangeLow(roundPrice(orl))
                    .volumeFilterSkipped(!candleLoad.hasVolume())
                    .vwapFilterSkipped(!candleLoad.hasVolume())
                    .build();
        }

        List<StrategyCandle> completedBreakoutCandidates = candles.stream()
                .filter(c -> !c.time().isBefore(OR_END))
                .filter(c -> !c.time().plusMinutes(CANDLE_MINUTES).isAfter(now.toLocalTime()))
                .toList();

        for (StrategyCandle breakout : completedBreakoutCandidates) {
            if (!isBreakout(definition.optionType(), breakout, orh, orl)) {
                continue;
            }

            FilterResult filters = evaluateFilters(definition.optionType(), candles, breakout, candleLoad.hasVolume());
            if (!filters.passed()) {
                continue;
            }

            Optional<StrategyCandle> nextCandle = candles.stream()
                    .filter(c -> c.time().equals(breakout.time().plusMinutes(CANDLE_MINUTES)))
                    .findFirst();
            if (nextCandle.isEmpty()) {
                return StrategyApplyResponse.builder()
                        .status("waiting")
                        .message("Breakout confirmed; waiting for next 5-minute candle open.")
                        .templateId(definition.id())
                        .symbol(symbol)
                        .direction(definition.optionType())
                        .openingRangeHigh(roundPrice(orh))
                        .openingRangeLow(roundPrice(orl))
                        .breakoutClose(roundPrice(breakout.close()))
                        .breakoutVolume(breakout.volume() != null ? breakout.volume().doubleValue() : null)
                        .averageVolume(filters.averageVolume())
                        .vwap(filters.vwap())
                        .volumeFilterPassed(filters.volumePassed())
                        .vwapFilterPassed(filters.vwapPassed())
                        .volumeFilterSkipped(filters.volumeSkipped())
                        .vwapFilterSkipped(filters.vwapSkipped())
                        .build();
            }

            TriggerRequest trigger = buildTriggerRequest(request, definition, symbol, spotScript, breakout, nextCandle.get());
            log.info("ORB strategy breakout confirmed template={} symbol={} direction={} breakoutTime={} breakoutClose={} ORH={} ORL={} spotEntry={} spotSL={} target1={} target2={} target3={} volumeSkipped={} vwapSkipped={}",
                    definition.id(),
                    symbol,
                    definition.optionType(),
                    breakout.time(),
                    roundPrice(breakout.close()),
                    roundPrice(orh),
                    roundPrice(orl),
                    trigger.getEntryPrice(),
                    trigger.getStopLoss(),
                    trigger.getTarget1(),
                    trigger.getTarget2(),
                    trigger.getTarget3(),
                    filters.volumeSkipped(),
                    filters.vwapSkipped());
            TriggerTradeRequestEntity existing = findExisting(trigger);
            if (existing != null) {
                return StrategyApplyResponse.builder()
                        .status("duplicate")
                        .message("A pending request already exists for this strategy contract.")
                        .templateId(definition.id())
                        .symbol(symbol)
                        .direction(definition.optionType())
                        .openingRangeHigh(roundPrice(orh))
                        .openingRangeLow(roundPrice(orl))
                        .breakoutClose(roundPrice(breakout.close()))
                        .breakoutVolume(breakout.volume() != null ? breakout.volume().doubleValue() : null)
                        .averageVolume(filters.averageVolume())
                        .vwap(filters.vwap())
                        .volumeFilterPassed(filters.volumePassed())
                        .vwapFilterPassed(filters.vwapPassed())
                        .volumeFilterSkipped(filters.volumeSkipped())
                        .vwapFilterSkipped(filters.vwapSkipped())
                        .triggerRequest(trigger)
                        .tradeRequest(existing)
                        .build();
            }

            TriggerTradeRequestEntity saved = tradeExecutionService.executeTriggeredTrade(trigger);
            return StrategyApplyResponse.builder()
                    .status("triggered")
                    .message("ORB breakout confirmed and strategy entry triggered immediately.")
                    .templateId(definition.id())
                    .symbol(symbol)
                    .direction(definition.optionType())
                    .openingRangeHigh(roundPrice(orh))
                    .openingRangeLow(roundPrice(orl))
                    .breakoutClose(roundPrice(breakout.close()))
                    .breakoutVolume(breakout.volume() != null ? breakout.volume().doubleValue() : null)
                    .averageVolume(filters.averageVolume())
                    .vwap(filters.vwap())
                    .volumeFilterPassed(filters.volumePassed())
                    .vwapFilterPassed(filters.vwapPassed())
                    .volumeFilterSkipped(filters.volumeSkipped())
                    .vwapFilterSkipped(filters.vwapSkipped())
                    .triggerRequest(trigger)
                    .tradeRequest(saved)
                    .build();
        }

        return StrategyApplyResponse.builder()
                .status("waiting")
                .message("No ORB breakout candle has passed the configured filters yet.")
                .templateId(definition.id())
                .symbol(symbol)
                .direction(definition.optionType())
                .openingRangeHigh(roundPrice(orh))
                .openingRangeLow(roundPrice(orl))
                .volumeFilterSkipped(!candleLoad.hasVolume())
                .vwapFilterSkipped(!candleLoad.hasVolume())
                .build();
    }

    private void validate(StrategyApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!DEFINITIONS.containsKey(normalizeTemplateId(request.getTemplateId()))) {
            throw new IllegalArgumentException("Unknown strategy template: " + request.getTemplateId());
        }
        if (!StringUtils.hasText(request.getSymbol())) {
            throw new IllegalArgumentException("symbol is required");
        }
    }

    private StrategyApplyResponse waiting(StrategyDefinition definition, String symbol, String message) {
        return StrategyApplyResponse.builder()
                .status("waiting")
                .message(message)
                .templateId(definition.id())
                .symbol(symbol)
                .direction(definition.optionType())
                .build();
    }

    private CandleLoad loadCandles(ScriptMasterEntity spotScript) {
        try {
            String symbol = spotScript != null ? spotScript.getTradingSymbol() : null;
            HardcodedMStockIndex hardcodedIndex = hardcodedMStockIndex(spotScript);
            if (hardcodedIndex != null) {
                return loadHardcodedIndexCandles(hardcodedIndex, symbol);
            }

            Optional<String> keyOpt = mStockInstrumentResolver.resolveInstrumentKey(spotScript);
            if (keyOpt.isEmpty()) {
                String reason = "Unable to resolve MStock instrument key for symbol=" + symbol
                        + ", exchange=" + (spotScript != null ? spotScript.getExchange() : null)
                        + ", scripCode=" + (spotScript != null ? spotScript.getScripCode() : null);
                log.warn(reason);
                printDiagnostic(reason);
                return new CandleLoad(List.of(), false, reason);
            }

            String key = keyOpt.get();
            Optional<MStockInstrumentEntity> instrumentOpt = mStockInstrumentRepository.findByInstrumentKey(key);
            if (instrumentOpt.isEmpty()) {
                String reason = "MStock instrument master row not found for key=" + key
                        + ". Refresh the MStock script master before loading intraday candles.";
                log.warn(reason);
                printDiagnostic(reason);
                return new CandleLoad(List.of(), false, reason);
            }

            MStockInstrumentEntity instrument = instrumentOpt.get();
            String symbolToken = StringUtils.hasText(instrument.getExchangeToken())
                    ? instrument.getExchangeToken().trim()
                    : null;
            if (!StringUtils.hasText(symbolToken)) {
                String reason = "MStock exchangeToken is missing for key=" + key
                        + ", instrumentToken=" + instrument.getInstrumentToken()
                        + ", tradingSymbol=" + instrument.getTradingSymbol()
                        + ", exchange=" + instrument.getExchange();
                log.warn(reason);
                printDiagnostic(reason);
                return new CandleLoad(List.of(), false, reason);
            }

            String exchange = key.contains(":") ? key.substring(0, key.indexOf(':')) : normalizeMStockExchange(spotScript.getExchange());
            log.info("Loading MStock intraday candles for symbol={}, key={}, exchange={}, symbolToken={}, interval={}",
                    symbol, key, exchange, symbolToken, "5minute");
            printDiagnostic("Loading candles symbol=" + symbol
                    + ", key=" + key
                    + ", exchange=" + exchange
                    + ", symbolToken=" + symbolToken
                    + ", interval=5minute");
            List<StrategyCandle> candles = mStockIntradayCandleService
                    .getIntradayCandles(exchange, symbolToken, "5minute")
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
                    + ", interval=5minute";
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

    private CandleLoad loadHardcodedIndexCandles(HardcodedMStockIndex index, String symbol) {
        String key = index.exchange() + ":" + index.script();
        printDiagnostic("Loading hardcoded index candles symbol=" + symbol
                + ", key=" + key
                + ", exchange=" + index.exchange()
                + ", symbolToken=" + index.exchangeToken()
                + ", name=" + index.name()
                + ", interval=5minute");
        List<StrategyCandle> candles = mStockIntradayCandleService
                .getIntradayCandles(index.exchange(), index.exchangeToken(), "5minute")
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
                + ", interval=5minute";
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

    private String normalizeSymbolKey(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return "";
        }
        return symbol.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private String summarizeCandleDates(List<StrategyCandle> candles) {
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

    private String summarizeCandleTimes(List<StrategyCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            return "none";
        }
        return candles.stream()
                .map(c -> c.time().toString())
                .limit(20)
                .toList()
                .toString();
    }

    private void printDiagnostic(String message) {
        System.out.println("[MSTOCK-STRATEGY] " + message);
    }

    private boolean isBreakout(String optionType, StrategyCandle candle, double orh, double orl) {
        if ("PE".equalsIgnoreCase(optionType)) {
            return candle.close() < orl;
        }
        return candle.close() > orh;
    }

    private FilterResult evaluateFilters(String optionType,
                                         List<StrategyCandle> candles,
                                         StrategyCandle breakout,
                                         boolean hasVolume) {
        if (!hasVolume || !breakout.hasVolume()) {
            return new FilterResult(true, null, null, null, null, true, true);
        }

        List<StrategyCandle> previous = candles.stream()
                .filter(c -> c.hasVolume() && c.time().isBefore(breakout.time()))
                .toList();
        List<StrategyCandle> volumeSample = previous.size() > AVERAGE_VOLUME_CANDLES
                ? previous.subList(previous.size() - AVERAGE_VOLUME_CANDLES, previous.size())
                : previous;
        Double averageVolume = volumeSample.isEmpty()
                ? null
                : volumeSample.stream().mapToLong(StrategyCandle::volume).average().orElse(Double.NaN);
        Boolean volumePassed = averageVolume == null || breakout.volume() > averageVolume * VOLUME_MULTIPLIER;

        Double vwap = computeVwap(candles, breakout);
        Boolean vwapPassed = vwap == null
                || ("PE".equalsIgnoreCase(optionType) ? breakout.close() < vwap : breakout.close() > vwap);

        return new FilterResult(Boolean.TRUE.equals(volumePassed) && Boolean.TRUE.equals(vwapPassed),
                roundNullable(averageVolume), roundNullable(vwap), volumePassed, vwapPassed, false, vwap == null);
    }

    private Double computeVwap(List<StrategyCandle> candles, StrategyCandle through) {
        double priceVolume = 0d;
        long totalVolume = 0L;
        for (StrategyCandle candle : candles) {
            if (candle.time().isAfter(through.time())) {
                break;
            }
            if (!candle.hasVolume()) {
                continue;
            }
            double typical = (candle.high() + candle.low() + candle.close()) / 3.0d;
            priceVolume += typical * candle.volume();
            totalVolume += candle.volume();
        }
        if (totalVolume <= 0L) {
            return null;
        }
        return priceVolume / totalVolume;
    }

    private TriggerRequest buildTriggerRequest(StrategyApplyRequest request,
                                               StrategyDefinition definition,
                                               String symbol,
                                               ScriptMasterEntity spotScript,
                                               StrategyCandle breakout,
                                               StrategyCandle nextCandle) {
        double entry = roundPrice(nextCandle.open());
        double stopLoss = "PE".equalsIgnoreCase(definition.optionType())
                ? roundPrice(breakout.high())
                : roundPrice(breakout.low());
        double risk = "PE".equalsIgnoreCase(definition.optionType())
                ? stopLoss - entry
                : entry - stopLoss;
        if (!Double.isFinite(risk) || risk <= 0d) {
            throw new IllegalArgumentException("ORB risk is not valid. Entry=" + entry + ", SL=" + stopLoss);
        }

        String expiry = nearestExpiry(symbol, definition.optionType());
        double strike = nearestStrike(symbol, definition.optionType(), expiry, entry);

        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(symbol);
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stopLoss);
        if ("PE".equalsIgnoreCase(definition.optionType())) {
            trigger.setTarget1(roundPrice(entry - risk));
            trigger.setTarget2(roundPrice(entry - (2d * risk)));
            trigger.setTarget3(roundPrice(entry - (3d * risk)));
        } else {
            trigger.setTarget1(roundPrice(entry + risk));
            trigger.setTarget2(roundPrice(entry + (2d * risk)));
            trigger.setTarget3(roundPrice(entry + (3d * risk)));
        }
        trigger.setStrikePrice(strike);
        trigger.setOptionType(definition.optionType());
        trigger.setExpiry(expiry);
        trigger.setIntraday(request.getIntraday() != null ? request.getIntraday() : true);
        trigger.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + definition.id());
        trigger.setUseSpotPrice(true);
        trigger.setUseSpotForEntry(true);
        trigger.setUseSpotForSl(true);
        trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(spotScript.getScripCode());
        trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId());
        if (request.getLots() != null && request.getLots() > 0) {
            trigger.setQuantity(request.getLots());
            trigger.setLots(request.getLots());
        }
        return trigger;
    }

    private void warmUpAtmOptionLtp(StrategyApplyRequest request,
                                    StrategyDefinition definition,
                                    String symbol,
                                    double orh,
                                    double orl) {
        try {
            String expiry = nearestExpiry(symbol, definition.optionType());
            double referencePrice = "PE".equalsIgnoreCase(definition.optionType()) ? orl : orh;
            double strike = nearestStrike(symbol, definition.optionType(), expiry, referencePrice);

            TriggerRequest warmup = new TriggerRequest();
            warmup.setInstrument(symbol);
            warmup.setStrikePrice(strike);
            warmup.setOptionType(definition.optionType());
            warmup.setExpiry(expiry);
            warmup.setUserId(request.getUserId());
            warmup.setBrokerCredentialsId(request.getBrokerCredentialsId());
            warmup.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + definition.id());

            tradeExecutionService.warmUpOptionLtp(warmup, "ORB opening range complete");
        } catch (Exception e) {
            log.warn("Unable to warm ATM option LTP for strategy template={} symbol={} direction={}: {}",
                    definition.id(), symbol, definition.optionType(), e.getMessage());
            log.debug("Strategy ATM option warmup failed", e);
        }
    }

    private TriggerTradeRequestEntity findExisting(TriggerRequest trigger) {
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

    private ScriptMasterEntity resolveSpotScript(String symbol) {
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

    private ScriptMasterEntity findSpotScript(String symbol, String exchange) {
        return scriptMasterRepository.findByExchangeIgnoreCase(exchange).stream()
                .filter(s -> s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(symbol))
                .filter(s -> s.getStrikePrice() == null || Math.abs(s.getStrikePrice()) < 0.001d)
                .filter(s -> !StringUtils.hasText(s.getExpiry()))
                .findFirst()
                .orElse(null);
    }

    private String nearestExpiry(String symbol, String optionType) {
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

    private double nearestStrike(String symbol, String optionType, String expiry, double referencePrice) {
        return scriptMasterRepository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry(symbol, optionType, expiry)
                .stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(strike -> Math.abs(strike - referencePrice)))
                .orElseThrow(() -> new IllegalArgumentException("No strike found for " + symbol + " " + optionType + " " + expiry));
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

    private String normalizeTemplateId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeMStockExchange(String exchange) {
        if ("NC".equalsIgnoreCase(exchange)) return "NSE";
        if ("BC".equalsIgnoreCase(exchange)) return "BSE";
        if ("NF".equalsIgnoreCase(exchange)) return "NFO";
        if ("BF".equalsIgnoreCase(exchange)) return "BFO";
        return exchange;
    }

    private double roundPrice(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private Double roundNullable(Double value) {
        return value == null || !Double.isFinite(value) ? null : roundPrice(value);
    }

    private record StrategyDefinition(String id, String name, String description, String optionType) {
    }

    private record CandleLoad(List<StrategyCandle> candles, boolean hasVolume, String reason) {
    }

    private record HardcodedMStockIndex(String script, String exchangeToken, String name, String exchange) {
    }

    private record StrategyCandle(LocalDate date,
                                  LocalTime time,
                                  double open,
                                  double high,
                                  double low,
                                  double close,
                                  Long volume) {
        private boolean hasVolume() {
            return volume != null && volume > 0L;
        }
    }

    private record FilterResult(boolean passed,
                                Double averageVolume,
                                Double vwap,
                                Boolean volumePassed,
                                Boolean vwapPassed,
                                Boolean volumeSkipped,
                                Boolean vwapSkipped) {
    }
}
