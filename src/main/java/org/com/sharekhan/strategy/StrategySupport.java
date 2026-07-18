package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockIntradayCandleService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.SpotSymbolAliases;
import org.com.sharekhan.service.TradeExecutionService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategySupport {

    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    public static final int CANDLE_MINUTES = 5;

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
        try {
            String symbol = spotScript != null ? spotScript.getTradingSymbol() : null;
            HardcodedMStockIndex hardcodedIndex = hardcodedMStockIndex(spotScript);
            if (hardcodedIndex != null) {
                return loadHardcodedIndexCandles(hardcodedIndex, symbol);
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

        return resolveBseSpotFallback(spotScript);
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
}
