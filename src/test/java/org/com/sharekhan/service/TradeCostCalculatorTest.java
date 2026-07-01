package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCostCalculatorTest {

    private final TradeCostCalculator calculator = new TradeCostCalculator();

    @Test
    void calculatesTradeCostAndEffectivePnlForRegularOptionExit() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .actualEntryPrice(100.0)
                .exitPrice(120.0)
                .quantity(50L)
                .lots(2)
                .pnl(1000.0)
                .exitReason("TARGET")
                .build();

        TradeCostCalculator.TradeCharges charges = calculator.calculate(trade);

        assertThat(charges.brokerage()).isEqualTo(20.0);
        assertThat(charges.stt()).isEqualTo(6.0);
        assertThat(charges.exchangeTransactionCharges()).isEqualTo(3.85);
        assertThat(charges.stampCharges()).isEqualTo(0.15);
        assertThat(charges.sebiTransactionFees()).isEqualTo(0.01);
        assertThat(charges.gst()).isEqualTo(4.30);
        assertThat(charges.totalTradeCost()).isEqualTo(34.31);
        assertThat(charges.effectivePnl()).isEqualTo(965.69);
        assertThat(charges.exercised()).isFalse();
    }

    @Test
    void appliesExercisedSttRateWhenExitReasonIdentifiesExercise() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .entryPrice(100.0)
                .exitPrice(120.0)
                .quantity(50L)
                .lots(2)
                .pnl(1000.0)
                .exitReason("Option exercised at expiry")
                .build();

        TradeCostCalculator.TradeCharges charges = calculator.calculate(trade);

        assertThat(charges.stt()).isEqualTo(7.50);
        assertThat(charges.totalTradeCost()).isEqualTo(35.81);
        assertThat(charges.effectivePnl()).isEqualTo(964.19);
        assertThat(charges.exercised()).isTrue();
    }

    @Test
    void doesNotCalculateChargesBeforeAnExitPriceIsKnown() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .entryPrice(100.0)
                .quantity(50L)
                .lots(1)
                .build();

        assertThat(calculator.calculate(trade)).isNull();
    }
}
