package org.com.sharekhan.strategy;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
abstract class AbstractOrbStrategy implements StrategyEvaluator {

    private static final LocalTime OR_START = LocalTime.of(9, 15);
    private static final LocalTime OR_END = LocalTime.of(9, 30);
    private static final int AVERAGE_VOLUME_CANDLES = 20;
    private static final double VOLUME_MULTIPLIER = 1.5d;

    protected final StrategySupport support;
    private final StrategyMetadata metadata;

    protected AbstractOrbStrategy(StrategySupport support, StrategyMetadata metadata) {
        this.support = support;
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
        if (now.toLocalTime().isBefore(OR_END)) {
            return support.waiting(metadata, symbol, "Waiting for opening range 9:15-9:30 to complete.");
        }

        CandleLoad candleLoad = support.loadCandles(spotScript);
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
                        + support.summarizeCandleDates(candleLoad.candles());
                log.warn(reason);
            }
            String detail = StringUtils.hasText(reason) ? " Reason: " + reason : "";
            return support.waiting(metadata, symbol, "No intraday candles available for " + symbol + "." + detail);
        }

        List<StrategyCandle> openingRange = candles.stream()
                .filter(c -> !c.time().isBefore(OR_START) && c.time().isBefore(OR_END))
                .toList();
        if (openingRange.size() < 3) {
            String reason = "Opening range 9:15-9:30 is not complete. Found "
                    + openingRange.size()
                    + " candles in range. Available candle times: "
                    + support.summarizeCandleTimes(candles);
            log.warn(reason);
            return support.waiting(metadata, symbol, reason);
        }

        double orh = openingRange.stream().mapToDouble(StrategyCandle::high).max().orElseThrow();
        double orl = openingRange.stream().mapToDouble(StrategyCandle::low).min().orElseThrow();
        support.warmUpAtmOptionLtp(request, metadata, symbol, "PE".equalsIgnoreCase(metadata.optionType()) ? orl : orh);

        if (now.toLocalTime().isBefore(OR_END.plusMinutes(StrategySupport.CANDLE_MINUTES))) {
            return StrategyApplyResponse.builder()
                    .status("waiting")
                    .message("Opening range complete; ATM option LTP subscription warmed. Waiting for first completed 5-minute candle after 9:30.")
                    .templateId(metadata.id())
                    .symbol(symbol)
                    .direction(metadata.optionType())
                    .openingRangeHigh(support.roundPrice(orh))
                    .openingRangeLow(support.roundPrice(orl))
                    .volumeFilterSkipped(!candleLoad.hasVolume())
                    .vwapFilterSkipped(!candleLoad.hasVolume())
                    .build();
        }

        List<StrategyCandle> completedBreakoutCandidates = candles.stream()
                .filter(c -> !c.time().isBefore(OR_END))
                .filter(c -> !c.time().plusMinutes(StrategySupport.CANDLE_MINUTES).isAfter(now.toLocalTime()))
                .toList();

        for (StrategyCandle breakout : completedBreakoutCandidates) {
            if (!isBreakout(breakout, orh, orl)) {
                continue;
            }

            FilterResult filters = evaluateFilters(candles, breakout, candleLoad.hasVolume());
            if (!filters.passed()) {
                continue;
            }

            Optional<StrategyCandle> nextCandle = candles.stream()
                    .filter(c -> c.time().equals(breakout.time().plusMinutes(StrategySupport.CANDLE_MINUTES)))
                    .findFirst();
            if (nextCandle.isEmpty()) {
                return StrategyApplyResponse.builder()
                        .status("waiting")
                        .message("Breakout confirmed; waiting for next 5-minute candle open.")
                        .templateId(metadata.id())
                        .symbol(symbol)
                        .direction(metadata.optionType())
                        .openingRangeHigh(support.roundPrice(orh))
                        .openingRangeLow(support.roundPrice(orl))
                        .breakoutClose(support.roundPrice(breakout.close()))
                        .breakoutVolume(breakout.volume() != null ? breakout.volume().doubleValue() : null)
                        .averageVolume(filters.averageVolume())
                        .vwap(filters.vwap())
                        .volumeFilterPassed(filters.volumePassed())
                        .vwapFilterPassed(filters.vwapPassed())
                        .volumeFilterSkipped(filters.volumeSkipped())
                        .vwapFilterSkipped(filters.vwapSkipped())
                        .build();
            }

            TriggerRequest trigger = buildTriggerRequest(request, symbol, spotScript, breakout, nextCandle.get());
            TriggerTradeRequestEntity existing = support.findExisting(trigger);
            if (existing != null) {
                return response("duplicate", "A pending request already exists for this strategy contract.",
                        symbol, orh, orl, breakout, filters, trigger, existing);
            }

            TriggerTradeRequestEntity saved = support.executeTriggeredTrade(trigger);
            return response("triggered", "ORB breakout confirmed and strategy entry triggered immediately.",
                    symbol, orh, orl, breakout, filters, trigger, saved);
        }

        return StrategyApplyResponse.builder()
                .status("waiting")
                .message("No ORB breakout candle has passed the configured filters yet.")
                .templateId(metadata.id())
                .symbol(symbol)
                .direction(metadata.optionType())
                .openingRangeHigh(support.roundPrice(orh))
                .openingRangeLow(support.roundPrice(orl))
                .volumeFilterSkipped(!candleLoad.hasVolume())
                .vwapFilterSkipped(!candleLoad.hasVolume())
                .build();
    }

    private StrategyApplyResponse response(String status,
                                           String message,
                                           String symbol,
                                           double orh,
                                           double orl,
                                           StrategyCandle breakout,
                                           FilterResult filters,
                                           TriggerRequest trigger,
                                           TriggerTradeRequestEntity tradeRequest) {
        return StrategyApplyResponse.builder()
                .status(status)
                .message(message)
                .templateId(metadata.id())
                .symbol(symbol)
                .direction(metadata.optionType())
                .openingRangeHigh(support.roundPrice(orh))
                .openingRangeLow(support.roundPrice(orl))
                .breakoutClose(support.roundPrice(breakout.close()))
                .breakoutVolume(breakout.volume() != null ? breakout.volume().doubleValue() : null)
                .averageVolume(filters.averageVolume())
                .vwap(filters.vwap())
                .volumeFilterPassed(filters.volumePassed())
                .vwapFilterPassed(filters.vwapPassed())
                .volumeFilterSkipped(filters.volumeSkipped())
                .vwapFilterSkipped(filters.vwapSkipped())
                .triggerRequest(trigger)
                .tradeRequest(tradeRequest)
                .build();
    }

    private boolean isBreakout(StrategyCandle candle, double orh, double orl) {
        if ("PE".equalsIgnoreCase(metadata.optionType())) {
            return candle.close() < orl;
        }
        return candle.close() > orh;
    }

    private FilterResult evaluateFilters(List<StrategyCandle> candles,
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
                || ("PE".equalsIgnoreCase(metadata.optionType()) ? breakout.close() < vwap : breakout.close() > vwap);

        return new FilterResult(Boolean.TRUE.equals(volumePassed) && Boolean.TRUE.equals(vwapPassed),
                support.roundNullable(averageVolume), support.roundNullable(vwap), volumePassed, vwapPassed, false, vwap == null);
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
                                               String symbol,
                                               ScriptMasterEntity spotScript,
                                               StrategyCandle breakout,
                                               StrategyCandle nextCandle) {
        double entry = support.roundPrice(nextCandle.open());
        boolean pe = "PE".equalsIgnoreCase(metadata.optionType());
        double stopLoss = pe ? support.roundPrice(breakout.high()) : support.roundPrice(breakout.low());
        double risk = pe ? stopLoss - entry : entry - stopLoss;
        if (!Double.isFinite(risk) || risk <= 0d) {
            throw new IllegalArgumentException("ORB risk is not valid. Entry=" + entry + ", SL=" + stopLoss);
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
        trigger.setIntraday(request.getIntraday() != null ? request.getIntraday() : true);
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
}
