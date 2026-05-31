package org.com.sharekhan.strategy;

public record IndicatorSnapshot(StrategyCandle candle,
                                double supertrend,
                                double rsi,
                                double previousRsi,
                                double ema50,
                                double adx,
                                double plusDi,
                                double minusDi) {
}
