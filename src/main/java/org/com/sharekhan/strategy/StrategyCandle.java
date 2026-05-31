package org.com.sharekhan.strategy;

import java.time.LocalDate;
import java.time.LocalTime;

public record StrategyCandle(LocalDate date,
                             LocalTime time,
                             double open,
                             double high,
                             double low,
                             double close,
                             Long volume) {
    public boolean hasVolume() {
        return volume != null && volume > 0L;
    }
}
