package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class FnoMoverEntryRiskPolicyTest {
    private final Instant now = Instant.parse("2026-11-06T05:00:00Z");
    private LocalDateTime at(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata")); }
    private TriggeredTradeSetupEntity trade(String side) {
        return TriggeredTradeSetupEntity.builder().source(FnoMoverEntryRiskPolicy.SOURCE).optionType(side)
                .entryPrice(100d).stopLoss("CE".equals(side) ? 90d : 110d)
                .target1("CE".equals(side) ? 111d : 89d)
                .useSpotForSl(true).useSpotForTarget(true).build();
    }
    @ParameterizedTest @CsvSource({"CE,100,true", "CE,99.99,true", "CE,100.001,false", "PE,100,true", "PE,100.01,true", "PE,99.999,false"})
    void usesDirectionalRiskAndIncludesExactBoundary(String side, double spot, boolean allowed) {
        var t=trade(side);var check=FnoMoverEntryRiskPolicy.evaluate(t,spot,at(now),now);
        assertThat(check.applies()).isTrue();assertThat(check.allowed()).isEqualTo(allowed);
        assertThat(t.getEntryPrice()).isEqualTo(100);assertThat(t.getStopLoss()).isEqualTo("CE".equals(side)?90:110);
        assertThat(t.getTarget1()).isEqualTo("CE".equals(side)?111:89);
    }
    @ParameterizedTest @ValueSource(longs={0,4999,5000})
    void acceptsSpotUpToFiveSeconds(long age) {
        assertThat(FnoMoverEntryRiskPolicy.evaluate(trade("CE"),100d,at(now.minusMillis(age)),now).allowed()).isTrue();
    }
    @ParameterizedTest @ValueSource(longs={-1,5001,86400000})
    void rejectsFutureOrOldSpot(long age) {
        assertThat(FnoMoverEntryRiskPolicy.evaluate(trade("PE"),100d,at(now.minusMillis(age)),now).reason()).isEqualTo("FNO_ENTRY_RR_SPOT_UNAVAILABLE");
    }
    @ParameterizedTest @ValueSource(doubles={0,-1,Double.NaN,Double.POSITIVE_INFINITY})
    void rejectsInvalidSpot(double spot) {
        assertThat(FnoMoverEntryRiskPolicy.evaluate(trade("CE"),spot,at(now),now).allowed()).isFalse();
    }
    @Test void failsClosedForMissingSpotOrTimestamp() {
        assertThat(FnoMoverEntryRiskPolicy.evaluate(trade("CE"),null,at(now),now).allowed()).isFalse();
        assertThat(FnoMoverEntryRiskPolicy.evaluate(trade("CE"),100d,null,now).allowed()).isFalse();
    }
    @ParameterizedTest @CsvSource({"CE,90,111", "CE,111,111", "CE,112,111", "PE,110,89", "PE,89,89", "PE,88,89"})
    void rejectsAtStopOrPastTarget(String side,double spot,double target) {
        var t=trade(side);t.setTarget1(target);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(t,spot,at(now),now).reason()).isEqualTo("FNO_ENTRY_RR_INVALID_BRACKET");
    }
    @Test void validatesSpotBracketModeAndOptionDirection() {
        var t=trade("CE");t.setUseSpotForSl(false);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(t,100d,at(now),now).allowed()).isFalse();
        t=trade("CE");t.setTarget1(Double.NaN);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(t,100d,at(now),now).allowed()).isFalse();
        t=trade("CE");t.setOptionType(null);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(t,100d,at(now),now).allowed()).isFalse();
    }
    @Test void legacySpotModeIsAcceptedWithoutChangingLevels() {
        var t=trade("PE");t.setUseSpotForSl(null);t.setUseSpotForTarget(null);t.setUseSpotPrice(true);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(t,100d,at(now),now).allowed()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"strategy:STOCK_0925_MOVER_ATR_BREAKOUT","manual","strategy:MCX_NATURALGAS_SCALP","strategy:FNO_CURATED_ATR_BREAKOUT"})
    void doesNotAffectOtherStrategies(String source) {
        var t=trade("CE");t.setSource(source);
        var c=FnoMoverEntryRiskPolicy.evaluate(t,null,null,null);assertThat(c.applies()).isFalse();assertThat(c.allowed()).isTrue();
    }
    @Test void reviewedCgpowerAndAstralHaveDifferentAdmissionOutcomes() {
        var cg=trade("PE");cg.setStopLoss(749d);cg.setTarget1(735.81);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(cg,742.50,at(now),now).allowed()).isFalse();
        var astral=trade("PE");astral.setStopLoss(1244.94);astral.setTarget1(1222.92);
        assertThat(FnoMoverEntryRiskPolicy.evaluate(astral,1235.10,at(now),now).allowed()).isTrue();
    }
}
