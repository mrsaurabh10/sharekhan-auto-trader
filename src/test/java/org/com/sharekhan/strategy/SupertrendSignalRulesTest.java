package org.com.sharekhan.strategy;

import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import static org.assertj.core.api.Assertions.assertThat;

class SupertrendSignalRulesTest {
    private final SupertrendStrategyProperties properties = new SupertrendStrategyProperties();
    private final SupertrendSignalRules rules = new SupertrendSignalRules(properties);

    @Test void alignedTrendsAllowRsiPullbacksAndWiderBands() {
        assertThat(rules.evaluate("NIFTY", "CE", snapshot(true, 68, 69, 21)).passed()).isTrue();
        assertThat(rules.evaluate("NIFTY", "PE", snapshot(false, 32, 31, 21)).passed()).isTrue();
        properties.setRequireRsiDirection(true);
        assertThat(rules.evaluate("NIFTY", "CE", snapshot(true, 68, 69, 21)).failures())
                .extracting(SupertrendSignalRules.Failure::rule).containsExactly("RSI_DIRECTION");
        assertThat(rules.evaluate("NIFTY", "PE", snapshot(false, 32, 31, 21)).failures())
                .extracting(SupertrendSignalRules.Failure::rule).containsExactly("RSI_DIRECTION");
    }
    @Test void rsiBoundariesAreInclusiveAndOutsideValuesFail() {
        for (double rsi : new double[]{50,75}) assertThat(rules.evaluate("NIFTY", "CE", snapshot(true,rsi,rsi,21)).passed()).isTrue();
        for (double rsi : new double[]{25,50}) assertThat(rules.evaluate("NIFTY", "PE", snapshot(false,rsi,rsi,21)).passed()).isTrue();
        for (double rsi : new double[]{49.99,75.01,Double.NaN}) assertThat(rules.evaluate("NIFTY", "CE", snapshot(true,rsi,rsi,21)).failures()).extracting(SupertrendSignalRules.Failure::rule).contains("RSI_RANGE");
        for (double rsi : new double[]{24.99,50.01}) assertThat(rules.evaluate("NIFTY", "PE", snapshot(false,rsi,rsi,21)).failures()).extracting(SupertrendSignalRules.Failure::rule).contains("RSI_RANGE");
    }
    @Test void adxIsStrictAndBankAliasesUseBankThreshold() {
        assertThat(rules.evaluate("NIFTY", "CE", snapshot(true,60,60,20)).passed()).isFalse();
        for (String symbol : new String[]{"BANKNIFTY","NIFTYBANK"}) {
            assertThat(rules.evaluate(symbol,"CE",snapshot(true,60,60,18)).passed()).isFalse();
            assertThat(rules.evaluate(symbol,"CE",snapshot(true,60,60,18.01)).passed()).isTrue();
        }
    }
    @Test void candleColourCanBeConfiguredAndAllFailuresAreReported() {
        IndicatorSnapshot wrong = new IndicatorSnapshot(new StrategyCandle(LocalDate.of(2026,10,5),LocalTime.of(9,25),101,102,98,100,1L),101,60,60,101,21,10,20);
        assertThat(rules.evaluate("NIFTY","CE",wrong).failures()).extracting(SupertrendSignalRules.Failure::rule).containsExactly("SUPERTREND","EMA50","DI","CANDLE_COLOUR");
        properties.setRequireCandleColour(false);
        assertThat(rules.evaluate("NIFTY","CE",wrong).failures()).extracting(SupertrendSignalRules.Failure::rule).containsExactly("SUPERTREND","EMA50","DI");
    }
    static IndicatorSnapshot snapshot(boolean ce, double rsi, double previous, double adx) {
        return new IndicatorSnapshot(new StrategyCandle(LocalDate.of(2026,10,5),LocalTime.of(9,25),ce?99:101,102,98,100,1L),ce?95:105,rsi,previous,ce?96:104,adx,ce?30:15,ce?15:30);
    }
}
