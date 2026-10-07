package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SupertrendSignalRules {
    private final SupertrendStrategyProperties properties;

    public Result evaluate(String normalizedSymbol, String optionType, IndicatorSnapshot indicator) {
        boolean ce = "CE".equalsIgnoreCase(optionType);
        StrategyCandle candle = indicator.candle();
        List<Failure> failures = new ArrayList<>();
        double min = ce ? properties.getCeRsiMin() : properties.getPeRsiMin();
        double max = ce ? properties.getCeRsiMax() : properties.getPeRsiMax();
        double threshold = "BANKNIFTY".equals(normalizedSymbol) || "NIFTYBANK".equals(normalizedSymbol)
                ? properties.getBankniftyAdxThreshold() : properties.getDefaultAdxThreshold();

        if (!(ce ? candle.close() > indicator.supertrend() : candle.close() < indicator.supertrend())) {
            failures.add(new Failure("SUPERTREND", ce ? "close <= Supertrend" : "close >= Supertrend"));
        }
        if (!Double.isFinite(indicator.rsi()) || indicator.rsi() < min || indicator.rsi() > max) {
            failures.add(new Failure("RSI_RANGE", "RSI not in " + min + "-" + max));
        }
        if (properties.isRequireRsiDirection()
                && !(ce ? indicator.rsi() > indicator.previousRsi() : indicator.rsi() < indicator.previousRsi())) {
            failures.add(new Failure("RSI_DIRECTION", ce ? "RSI is not trending up" : "RSI is not declining"));
        }
        if (!(ce ? candle.close() > indicator.ema50() : candle.close() < indicator.ema50())) {
            failures.add(new Failure("EMA50", ce ? "close <= 50 EMA" : "close >= 50 EMA"));
        }
        if (!(indicator.adx() > threshold)) {
            failures.add(new Failure("ADX", "ADX <= " + threshold));
        }
        if (!(ce ? indicator.plusDi() > indicator.minusDi() : indicator.minusDi() > indicator.plusDi())) {
            failures.add(new Failure("DI", ce ? "+DI <= -DI" : "-DI <= +DI"));
        }
        if (properties.isRequireCandleColour() && !(ce ? candle.close() > candle.open() : candle.close() < candle.open())) {
            failures.add(new Failure("CANDLE_COLOUR", ce ? "entry candle is not green" : "entry candle is not red"));
        }
        return new Result(List.copyOf(failures));
    }

    public record Failure(String rule, String reason) { }

    public record Result(List<Failure> failures) {
        public boolean passed() { return failures.isEmpty(); }
        public String reason() {
            return passed() ? "All conditions passed." : String.join("; ", failures.stream().map(Failure::reason).toList());
        }
    }
}
