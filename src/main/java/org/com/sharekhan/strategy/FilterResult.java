package org.com.sharekhan.strategy;

public record FilterResult(boolean passed,
                           Double averageVolume,
                           Double vwap,
                           Boolean volumePassed,
                           Boolean vwapPassed,
                           Boolean volumeSkipped,
                           Boolean vwapSkipped) {
}
