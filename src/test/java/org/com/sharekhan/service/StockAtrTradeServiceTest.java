package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StockAtrTradeServiceTest {

    @Test
    void target3FallsBackToSixFiveMinuteAtrWhenLongFifteenMinuteTargetIsBelowTarget2() {
        double target3 = StockAtrTradeService.calculateTarget3(100.0, "LONG", 2.0, 8.0, 110.0);

        assertThat(target3).isEqualTo(112.0);
    }

    @Test
    void target3FallsBackToSixFiveMinuteAtrWhenShortFifteenMinuteTargetIsAboveTarget2() {
        double target3 = StockAtrTradeService.calculateTarget3(100.0, "SHORT", 2.0, 8.0, 90.0);

        assertThat(target3).isEqualTo(88.0);
    }

    @Test
    void target3KeepsFifteenMinuteAtrWhenItIsBeyondTarget2() {
        double target3 = StockAtrTradeService.calculateTarget3(100.0, "LONG", 2.0, 12.0, 110.0);

        assertThat(target3).isEqualTo(112.0);
    }
}
