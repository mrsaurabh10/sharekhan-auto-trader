package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

abstract class AbstractSupertrendRsiEmaAdxStrategy implements StrategyEvaluator {

    private static final double BANKNIFTY_ADX_THRESHOLD = 18.0d;
    private static final double NIFTY_ADX_THRESHOLD = 20.0d;

    protected final StrategySupport support;
    private final IndicatorService indicatorService;
    private final StrategyMetadata metadata;

    protected AbstractSupertrendRsiEmaAdxStrategy(StrategySupport support,
                                                  IndicatorService indicatorService,
                                                  StrategyMetadata metadata) {
        this.support = support;
        this.indicatorService = indicatorService;
        this.metadata = metadata;
    }

    @Override
    public StrategyMetadata metadata() {
        return metadata;
    }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);
        ScriptMasterEntity spotScript = support.resolveSpotScript(symbol);
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        int requiredCandles = Math.max(50, indicatorService.minimumCandles());
        CandleLoad candleLoad = support.loadCandlesWithHistoricalFallback(spotScript, requiredCandles);
        List<StrategyCandle> completedCandles = candleLoad.candles().stream()
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                .filter(c -> isCompleted(c, today, now))
                .toList();
        List<StrategyCandle> completedToday = completedCandles.stream()
                .filter(c -> today.equals(c.date()))
                .toList();

        if (completedCandles.isEmpty()) {
            String detail = StringUtils.hasText(candleLoad.reason()) ? " Reason: " + candleLoad.reason() : "";
            return support.waiting(metadata, symbol, "No completed 5-minute candles available for " + symbol + "." + detail);
        }
        if (completedToday.isEmpty()) {
            return support.waiting(metadata, symbol, "Waiting for first completed 5-minute candle for " + symbol + " today.");
        }
        if (completedCandles.size() < requiredCandles) {
            return support.waiting(metadata, symbol, "Waiting for enough 5-minute candles to compute Supertrend, RSI, 50 EMA, and ADX. Have "
                    + completedCandles.size() + ", need at least " + requiredCandles
                    + ". Today's completed candles: " + completedToday.size() + ".");
        }

        IndicatorSnapshot indicator = indicatorService.computeSnapshot(completedCandles);
        StrategyCandle signal = indicator.candle();
        DirectionalSignal signalResult = evaluateDirectionalSignal(symbol, signal, indicator);
        if (!signalResult.passed()) {
            return StrategyApplyResponse.builder()
                    .status("waiting")
                    .message("Latest completed 5-minute candle has not passed "
                            + metadata.name()
                            + ". "
                            + signalResult.reason())
                    .templateId(metadata.id())
                    .symbol(symbol)
                    .direction(metadata.optionType())
                    .breakoutClose(support.roundPrice(signal.close()))
                    .build();
        }

        TriggerRequest trigger = buildTriggerRequest(request, symbol, spotScript, signal);
        TriggerTradeRequestEntity existing = support.findExisting(trigger);
        if (existing != null) {
            return response("duplicate", "A pending request already exists for this strategy contract.", symbol, signal, trigger, existing);
        }

        TriggerTradeRequestEntity saved = support.executeTriggeredTrade(trigger);
        return response("triggered",
                metadata.name() + " conditions passed on the latest completed 5-minute candle and strategy entry triggered immediately.",
                symbol,
                signal,
                trigger,
                saved);
    }

    private StrategyApplyResponse response(String status,
                                           String message,
                                           String symbol,
                                           StrategyCandle signal,
                                           TriggerRequest trigger,
                                           TriggerTradeRequestEntity tradeRequest) {
        return StrategyApplyResponse.builder()
                .status(status)
                .message(message)
                .templateId(metadata.id())
                .symbol(symbol)
                .direction(metadata.optionType())
                .breakoutClose(support.roundPrice(signal.close()))
                .triggerRequest(trigger)
                .tradeRequest(tradeRequest)
                .build();
    }

    private DirectionalSignal evaluateDirectionalSignal(String symbol,
                                                        StrategyCandle candle,
                                                        IndicatorSnapshot indicator) {
        boolean ce = "CE".equalsIgnoreCase(metadata.optionType());
        List<String> failures = new ArrayList<>();
        if (ce) {
            if (!(candle.close() > indicator.supertrend())) failures.add("close <= Supertrend");
            if (!inRange(indicator.rsi(), 45.0d, 65.0d)) failures.add("RSI not in 45-65");
            if (!(indicator.rsi() > indicator.previousRsi())) failures.add("RSI is not trending up");
            if (!(candle.close() > indicator.ema50())) failures.add("close <= 50 EMA");
            if (!(indicator.adx() > adxThreshold(symbol))) failures.add("ADX below threshold");
            if (!(indicator.plusDi() > indicator.minusDi())) failures.add("+DI <= -DI");
            if (!(candle.close() > candle.open())) failures.add("entry candle is not green");
        } else {
            if (!(candle.close() < indicator.supertrend())) failures.add("close >= Supertrend");
            if (!inRange(indicator.rsi(), 35.0d, 55.0d)) failures.add("RSI not in 35-55");
            if (!(indicator.rsi() < indicator.previousRsi())) failures.add("RSI is not declining");
            if (!(candle.close() < indicator.ema50())) failures.add("close >= 50 EMA");
            if (!(indicator.adx() > adxThreshold(symbol))) failures.add("ADX below threshold");
            if (!(indicator.minusDi() > indicator.plusDi())) failures.add("-DI <= +DI");
            if (!(candle.close() < candle.open())) failures.add("entry candle is not red");
        }

        if (failures.isEmpty()) {
            return new DirectionalSignal(true, "All conditions passed.");
        }
        return new DirectionalSignal(false, String.join("; ", failures)
                + ". Values: close=" + support.roundPrice(candle.close())
                + ", ST=" + support.roundPrice(indicator.supertrend())
                + ", RSI=" + support.roundPrice(indicator.rsi())
                + ", prevRSI=" + support.roundPrice(indicator.previousRsi())
                + ", EMA50=" + support.roundPrice(indicator.ema50())
                + ", ADX=" + support.roundPrice(indicator.adx())
                + ", +DI=" + support.roundPrice(indicator.plusDi())
                + ", -DI=" + support.roundPrice(indicator.minusDi()) + ".");
    }

    private TriggerRequest buildTriggerRequest(StrategyApplyRequest request,
                                               String symbol,
                                               ScriptMasterEntity spotScript,
                                               StrategyCandle signal) {
        boolean pe = "PE".equalsIgnoreCase(metadata.optionType());
        double entry = support.roundPrice(signal.close());
        double stopLoss = pe ? support.roundPrice(signal.high()) : support.roundPrice(signal.low());
        double risk = pe ? stopLoss - entry : entry - stopLoss;
        if (!Double.isFinite(risk) || risk <= 0d) {
            throw new IllegalArgumentException(metadata.name() + " risk is not valid. Entry=" + entry + ", SL=" + stopLoss);
        }

        String expiry = support.nearestExpiry(symbol, metadata.optionType());
        double strike = support.nearestStrike(symbol, metadata.optionType(), expiry, entry);

        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(symbol);
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stopLoss);
        if (pe) {
            trigger.setTarget1(support.roundPrice(entry - risk));
            trigger.setTarget2(support.roundPrice(entry - (2d * risk)));
            trigger.setTarget3(support.roundPrice(entry - (3d * risk)));
        } else {
            trigger.setTarget1(support.roundPrice(entry + risk));
            trigger.setTarget2(support.roundPrice(entry + (2d * risk)));
            trigger.setTarget3(support.roundPrice(entry + (3d * risk)));
        }
        trigger.setStrikePrice(strike);
        trigger.setOptionType(metadata.optionType());
        trigger.setExpiry(expiry);
        // ADX strategy is always intraday by design.
        trigger.setIntraday(true);
        trigger.setSource(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + metadata.id());
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

    private boolean inRange(double value, double minInclusive, double maxInclusive) {
        return Double.isFinite(value) && value >= minInclusive && value <= maxInclusive;
    }

    private double adxThreshold(String symbol) {
        String normalized = support.normalizeSymbolKey(symbol);
        if ("BANKNIFTY".equals(normalized) || "NIFTYBANK".equals(normalized)) {
            return BANKNIFTY_ADX_THRESHOLD;
        }
        return NIFTY_ADX_THRESHOLD;
    }

    private boolean isCompleted(StrategyCandle candle, LocalDate today, LocalDateTime now) {
        if (candle.date().isBefore(today)) {
            return true;
        }
        if (!today.equals(candle.date())) {
            return false;
        }
        return !candle.time().plusMinutes(StrategySupport.CANDLE_MINUTES).isAfter(now.toLocalTime());
    }

    private record DirectionalSignal(boolean passed, String reason) {
    }
}
