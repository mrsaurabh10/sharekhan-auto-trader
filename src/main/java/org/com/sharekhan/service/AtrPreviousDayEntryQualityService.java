package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** Strict live confirmation for the ATR prior-day strategy. */
@Service
public class AtrPreviousDayEntryQualityService {
    private static final String SOURCE = "atr-pdh-pdl-strategy";
    private final MStockIntradayCandleService candles;

    @Value("${app.strategy.atr-prior-day.strict-entry.enabled:false}")
    private boolean enabled;
    @Value("${app.strategy.atr-prior-day.strict-entry.minimum-time:09:30}")
    private java.time.LocalTime minimumTime;
    @Value("${app.strategy.atr-prior-day.strict-entry.max-vwap-extension-atr:1.25}")
    private double maxVwapExtensionAtr;

    public AtrPreviousDayEntryQualityService(MStockIntradayCandleService candles) {
        this.candles = candles;
    }

    public Decision evaluate(TriggerTradeRequestEntity trigger, LocalDateTime now, double currentSpot) {
        if (!enabled || trigger == null || !SOURCE.equalsIgnoreCase(trigger.getSource())) return Decision.accepted();
        if (now.toLocalTime().isBefore(minimumTime)) return Decision.waiting("strict entry window starts at " + minimumTime);
        if (trigger.getSpotScripCode() == null || trigger.getEntryPrice() == null) return Decision.waiting("spot entry details unavailable");
        List<MStockIntradayCandleService.IntradayCandle> bars =
                candles.getCompletedFiveMinuteCandles(trigger.getSpotScripCode(), now);
        if (bars.size() < 3) return Decision.waiting("insufficient completed five-minute candles");
        boolean pe = "PE".equalsIgnoreCase(trigger.getOptionType());
        var last = bars.get(bars.size() - 1);
        var previous = bars.get(bars.size() - 2);
        if (!(pe ? last.close() <= trigger.getEntryPrice() && previous.close() <= trigger.getEntryPrice()
                : last.close() >= trigger.getEntryPrice() && previous.close() >= trigger.getEntryPrice())) {
            return Decision.waiting("two completed five-minute closes beyond entry are required");
        }
        double atr = atr(bars);
        double vwap = vwap(bars);
        // Intraday feeds only contain the current session, so use the longest
        // available rolling average up to 50 bars instead of delaying every
        // signal until the final hour of the market.
        int smaPeriod = Math.min(50, bars.size());
        int slopeLookback = Math.min(5, Math.max(1, smaPeriod / 3));
        double sma50 = bars.subList(bars.size() - smaPeriod, bars.size()).stream().mapToDouble(MStockIntradayCandleService.IntradayCandle::close).average().orElse(Double.NaN);
        int priorEnd = bars.size() - slopeLookback;
        int priorStart = Math.max(0, priorEnd - smaPeriod);
        double priorSma50 = bars.subList(priorStart, priorEnd)
                .stream().mapToDouble(MStockIntradayCandleService.IntradayCandle::close).average().orElse(Double.NaN);
        boolean trendAligned = pe ? last.close() < vwap && last.close() < sma50 && sma50 < priorSma50
                : last.close() > vwap && last.close() > sma50 && sma50 > priorSma50;
        if (!trendAligned) return Decision.waiting("five-minute VWAP/50-SMA trend is not aligned");
        if (atr <= 0d || Math.abs(currentSpot - vwap) > maxVwapExtensionAtr * atr) {
            return Decision.waiting("entry is too extended from VWAP");
        }
        return Decision.accepted();
    }

    private double atr(List<MStockIntradayCandleService.IntradayCandle> bars) {
        int period = Math.min(15, bars.size() - 1);
        List<MStockIntradayCandleService.IntradayCandle> tail = bars.subList(bars.size() - period - 1, bars.size());
        double total = 0d;
        for (int i = 1; i < tail.size(); i++) {
            var prior = tail.get(i - 1); var current = tail.get(i);
            total += Math.max(current.high() - current.low(), Math.max(Math.abs(current.high() - prior.close()), Math.abs(current.low() - prior.close())));
        }
        return total / period;
    }

    private double vwap(List<MStockIntradayCandleService.IntradayCandle> bars) {
        double priceVolume = 0d, volume = 0d;
        for (var bar : bars) {
            if (bar.volume() == null || bar.volume() <= 0L) continue;
            priceVolume += ((bar.high() + bar.low() + bar.close()) / 3d) * bar.volume();
            volume += bar.volume();
        }
        return volume > 0d ? priceVolume / volume : Double.NaN;
    }

    public record Decision(boolean ready, String reason) {
        static Decision accepted() { return new Decision(true, null); }
        static Decision waiting(String reason) { return new Decision(false, reason); }
    }
}
