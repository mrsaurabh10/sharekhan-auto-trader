package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCostCalculatorTest {

    private final TradeCostCalculator calculator = new TradeCostCalculator();

    private TriggeredTradeSetupEntity bankLeg(long quantity,int stage) {
        return TriggeredTradeSetupEntity.builder().exchange("NC").instrumentType("EQ").entryAction("SELL").intraday(true)
            .actualEntryPrice(125.65).exitPrice(126.50).quantity(quantity).lots((int)quantity).originalLots(3982)
            .targetOrderGroupId(10494L).targetStage(stage).build();
    }
    @Test void stagedBankindiaSharesAreNotOptionLotsAndEntryBrokerageIsShared() {
        double costs=0,brokerage=0,gross=0;
        for (int i=0;i<3;i++) {
            var trade=bankLeg(i==2?1328:1327,i+1);
            var charges=calculator.calculate(trade); costs+=charges.totalTradeCost();brokerage+=charges.brokerage();
            gross+=(125.65-126.50)*trade.getQuantity();
            assertThat(charges.totalTradeCost()).isLessThan(100);
            assertThat(charges.stt()).isCloseTo(125.65*trade.getQuantity()*0.00025,org.assertj.core.data.Offset.offset(0.01));
        }
        assertThat(brokerage).isCloseTo(80d,org.assertj.core.data.Offset.offset(0.02)); // 1 capped entry + 3 capped exits
        assertThat(costs).isLessThan(300d);
        System.out.printf(java.util.Locale.ROOT,"BANKINDIA_CASH_ESTIMATE gross=%.2f costs=%.2f net=%.2f brokerage=%.2f%n",gross,costs,gross-costs,brokerage);
        assertThat(gross).isCloseTo(-3384.70,org.assertj.core.data.Offset.offset(0.001));
    }
    @Test void cashShareCountInLotsNeverMultipliesCashBrokerage() {
        var trade=bankLeg(1327,1);
        var before=calculator.calculate(trade);trade.setLots(999999);
        assertThat(calculator.calculate(trade)).isEqualTo(before);
        assertThat(TradeCostCalculator.costModel(trade)).startsWith("cash-order-reference-estimate");
    }
    @Test void shortCoverStampAndSttUseTheirActualBuyAndSellSides() {
        var trade=TriggeredTradeSetupEntity.builder().exchange("NC").intraday(true).entryAction("SELL").quantity(1000L)
            .actualEntryPrice(100d).exitPrice(95d).build();
        var charges=calculator.calculate(trade);
        assertThat(charges.brokerage()).isEqualTo(40d);assertThat(charges.stt()).isEqualTo(25d);
        assertThat(charges.stampCharges()).isEqualTo(2.85d);
        assertThat(charges.exchangeTransactionCharges()).isEqualTo(5.99d);
        assertThat(charges.gst()).isEqualTo(8.31d);
        assertThat(charges.totalTradeCost()).isEqualTo(82.34d);
        assertThat(charges.effectivePnl()).isEqualTo(5000d-charges.totalTradeCost());
    }
    @Test void cashDisplayCopyRecomputesHistoricalCostsWithoutMutatingStoredValues() {
        var trade=bankLeg(1327,1);trade.setPnl(-1127.95);trade.setTradeCost(15969.08);trade.setEffectivePnl(-17097.03);
        var view=TradeCostCalculator.cashCostView(trade);
        assertThat(view).isNotSameAs(trade);assertThat(view.getTradeCost()).isLessThan(100d);
        assertThat(view.getEffectivePnl()).isGreaterThan(-1300d);
        assertThat(view.getPnl()).isEqualTo(trade.getPnl());assertThat(trade.getTradeCost()).isEqualTo(15969.08);
    }
    @Test void standardBseCashUsesItsOwnTransactionRate() {
        var trade=TriggeredTradeSetupEntity.builder().exchange("BC").intraday(true).quantity(1000L)
            .actualEntryPrice(100d).exitPrice(95d).build();
        assertThat(calculator.calculate(trade).exchangeTransactionCharges()).isEqualTo(7.31d);
    }

    @Test void deliveryCashUsesDeliveryRatesAndDoesNotUseShareLotsAsBrokerage() {
        var trade=TriggeredTradeSetupEntity.builder().exchange("NC").intraday(false).quantity(1000L).lots(1000)
            .actualEntryPrice(100d).exitPrice(110d).build();
        var charges=calculator.calculate(trade);
        assertThat(charges.brokerage()).isZero();assertThat(charges.stt()).isEqualTo(210d);
        assertThat(charges.stampCharges()).isEqualTo(15d);
    }

    @Test void shortCostsUseSellEntryAndBuyCoverAndBreakEvenMovesBelowEntry() {
        var trade=org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().entryAction("SELL").quantity(100L).actualEntryPrice(100d).exitPrice(95d).build();
        var charges=TradeCostCalculator.calculateCharges(trade);
        assertThat(charges.effectivePnl()).isEqualTo(500d-charges.totalTradeCost());
        Double cover=TradeCostCalculator.maximumProfitableCoverPrice(trade,0.05d);
        assertThat(cover).isLessThan(100d);
        trade.setExitPrice(cover);
        assertThat(TradeCostCalculator.calculateCharges(trade).effectivePnl()).isPositive();
    }

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
