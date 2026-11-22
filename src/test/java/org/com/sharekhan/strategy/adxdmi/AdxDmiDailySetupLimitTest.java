package org.com.sharekhan.strategy.adxdmi;

import java.util.List;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.execution.TradeExecutionSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdxDmiDailySetupLimitTest {
    private TriggeredTradeSetupEntity leg(String signal) {
        return TriggeredTradeSetupEntity.builder().strategyDecision("ADX_SIGNAL="+signal+";ADX_LEG=1;").build();
    }
    @Test void threeFilledLegsConsumeOneSetupAndAllowTheSecond() {
        assertDoesNotThrow(()->AdxDmiDailySetupLimit.requireAvailable(List.of(leg("first"),leg("first"),leg("first")),"second"));
    }
    @Test void twoDifferentFilledSignalsBlockTheThirdButAllowTheirOwnRemainingLegs() {
        var filled=List.of(leg("first"),leg("second"));
        assertThrows(IllegalStateException.class,()->AdxDmiDailySetupLimit.requireAvailable(filled,"third"));
        assertDoesNotThrow(()->AdxDmiDailySetupLimit.requireAvailable(filled,"second"));
    }
    @Test void rewrittenOutcomeCommentDoesNotEraseTheImmutableIdentity() {
        var a=leg("first");a.setComment("Closed at SL");var b=leg("first");b.setComment("Closed at T2");
        assertDoesNotThrow(()->AdxDmiDailySetupLimit.requireAvailable(List.of(a,b),"second"));
    }
    @Test void legacySignalAndServerTimeGroupIdentifySiblingLegs() {
        var a=new TriggeredTradeSetupEntity();a.setComment("signal=legacy; original fixed stop");
        var b=new TriggeredTradeSetupEntity();b.setComment("signal=legacy; original fixed stop");
        assertDoesNotThrow(()->AdxDmiDailySetupLimit.requireAvailable(List.of(a,b),"next"));
        var settings=new TradeExecutionSettings();settings.setTimeExitGroupId("persisted-group");
        a.setComment("Closed");a.setExecutionSettings(settings);b.setComment("Closed");b.setExecutionSettings(settings.copy());
        assertDoesNotThrow(()->AdxDmiDailySetupLimit.requireAvailable(List.of(a,b),"next"));
    }
    @Test void erasedLegacyFilledIdentityRequiresReconciliation() {
        assertThrows(IllegalStateException.class,()->AdxDmiDailySetupLimit.requireAvailable(List.of(new TriggeredTradeSetupEntity()),"next"));
    }
}
