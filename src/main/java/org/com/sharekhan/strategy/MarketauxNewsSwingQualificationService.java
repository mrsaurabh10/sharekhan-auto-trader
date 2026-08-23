package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Finds a confirmed five-minute swing that formed only after the news was observed. */
@Component
public class MarketauxNewsSwingQualificationService {

    private static final int ATR_PERIOD = 75;
    private final StrategySupport support;

    public MarketauxNewsSwingQualificationService(StrategySupport support) {
        this.support = support;
    }

    public Fno925EntryQualificationService.Qualification qualify(ScriptMasterEntity spot, String optionType,
                                                                   LocalDateTime newsObservedAt, LocalDateTime now) {
        if (newsObservedAt == null) return Fno925EntryQualificationService.Qualification.waiting("news observation time is unavailable");
        List<StrategyCandle> all = support.loadCandlesWithHistoricalFallback(spot, ATR_PERIOD + 1).candles().stream()
                .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time)).toList();
        double atr = atr(all);
        if (atr <= 0d) return Fno925EntryQualificationService.Qualification.waiting("ATR(75) 5-minute data is unavailable");
        List<StrategyCandle> newsSession = all.stream()
                .filter(candle -> !candle.date().isBefore(newsObservedAt.toLocalDate()))
                .filter(candle -> LocalDateTime.of(candle.date(), candle.time().plusMinutes(StrategySupport.CANDLE_MINUTES))
                        .isAfter(newsObservedAt))
                .filter(candle -> !LocalDateTime.of(candle.date(), candle.time().plusMinutes(StrategySupport.CANDLE_MINUTES)).isAfter(now))
                .toList();
        if (newsSession.size() < 5) {
            return Fno925EntryQualificationService.Qualification.waiting("waiting for five completed 5-minute candles after the news was observed");
        }
        boolean ce = "CE".equalsIgnoreCase(optionType);
        double latestClose = newsSession.get(newsSession.size() - 1).close();
        List<StrategyCandle> pivots = java.util.stream.IntStream.range(2, newsSession.size() - 2)
                .filter(index -> isSwing(newsSession, index, ce))
                .mapToObj(newsSession::get)
                .filter(candle -> ce ? candle.high() > latestClose : candle.low() < latestClose)
                .toList();
        StrategyCandle selected = pivots.stream()
                .min(Comparator.comparingDouble(candle -> ce ? candle.high() - latestClose : latestClose - candle.low()))
                .orElse(null);
        if (selected == null) {
            return Fno925EntryQualificationService.Qualification.waiting("no unbroken post-news 5-minute swing "
                    + (ce ? "high" : "low") + " is available yet");
        }
        double entry = ce ? selected.high() : selected.low();
        return Fno925EntryQualificationService.Qualification.qualified(new Fno925EntryQualificationService.Signal(
                support.roundPrice(entry), 0d, support.roundPrice(atr), selected.high(), selected.low(), selected,
                ce ? "POST_NEWS_SWING_HIGH_BREAKOUT" : "POST_NEWS_SWING_LOW_BREAKDOWN"));
    }

    private boolean isSwing(List<StrategyCandle> candles, int index, boolean high) {
        double value = high ? candles.get(index).high() : candles.get(index).low();
        for (int offset = 1; offset <= 2; offset++) {
            double before = high ? candles.get(index - offset).high() : candles.get(index - offset).low();
            double after = high ? candles.get(index + offset).high() : candles.get(index + offset).low();
            if (high ? value <= before || value <= after : value >= before || value >= after) return false;
        }
        return true;
    }

    private double atr(List<StrategyCandle> candles) {
        if (candles.size() < ATR_PERIOD + 1) return 0d;
        List<StrategyCandle> sample = candles.subList(candles.size() - ATR_PERIOD - 1, candles.size());
        double total = 0d;
        for (int index = 1; index < sample.size(); index++) {
            StrategyCandle current = sample.get(index), previous = sample.get(index - 1);
            total += Math.max(current.high() - current.low(), Math.max(
                    Math.abs(current.high() - previous.close()), Math.abs(current.low() - previous.close())));
        }
        return total / ATR_PERIOD;
    }
}
