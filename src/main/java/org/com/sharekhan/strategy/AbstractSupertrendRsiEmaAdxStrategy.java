package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

abstract class AbstractSupertrendRsiEmaAdxStrategy implements StrategyEvaluator {

    protected final StrategySupport support;
    private final IndicatorService indicatorService;
    private final StrategyMetadata metadata;
    private final SupertrendSignalRules rules;
    private final SupertrendDecisionDiagnostics diagnostics;

    protected AbstractSupertrendRsiEmaAdxStrategy(StrategySupport support,
                                                  IndicatorService indicatorService,
                                                  StrategyMetadata metadata,
                                                  SupertrendSignalRules rules,
                                                  SupertrendDecisionDiagnostics diagnostics) {
        this.support = support;
        this.indicatorService = indicatorService;
        this.metadata = metadata;
        this.rules = rules;
        this.diagnostics = diagnostics;
    }

    @Override
    public StrategyMetadata metadata() {
        return metadata;
    }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        return apply(request, LocalDateTime.now(StrategySupport.MARKET_ZONE));
    }

    StrategyApplyResponse apply(StrategyApplyRequest request, LocalDateTime now) {
        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);
        ScriptMasterEntity spotScript = support.resolveSpotScript(symbol);
        LocalDate today = now.toLocalDate();
        int requiredCandles = Math.max(50, indicatorService.minimumCandles());
        CandleLoad candleLoad = support.loadCompletedIndicatorCandles(spotScript, requiredCandles, now);
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

        StrategyCandle latest = completedToday.get(completedToday.size() - 1);
        if (!LocalDateTime.of(latest.date(), latest.time()).plusMinutes(2L * StrategySupport.CANDLE_MINUTES).isAfter(now)) {
            return support.waiting(metadata, symbol, "Waiting for a fresh completed five-minute candle; latest candle starts at "
                    + latest.time() + ". Cached history is available, but today's feed has not advanced.");
        }

        IndicatorSnapshot indicator = indicatorService.computeSnapshot(completedCandles);
        StrategyCandle signal = indicator.candle();
        SupertrendSignalRules.Result signalResult = rules.evaluate(support.normalizeSymbolKey(symbol), metadata.optionType(), indicator);
        diagnostics.record(request, metadata, symbol, indicator, signalResult);
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
        trigger.setTslEnabled(request.getLots() != null && request.getLots() > 1);
        if (request.getLots() != null && request.getLots() > 0) {
            trigger.setQuantity(request.getLots());
            trigger.setLots(request.getLots());
        }
        return trigger;
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

}
