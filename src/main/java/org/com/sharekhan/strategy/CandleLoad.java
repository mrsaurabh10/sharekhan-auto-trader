package org.com.sharekhan.strategy;

import java.util.List;

public record CandleLoad(List<StrategyCandle> candles, boolean hasVolume, String reason) {
}
