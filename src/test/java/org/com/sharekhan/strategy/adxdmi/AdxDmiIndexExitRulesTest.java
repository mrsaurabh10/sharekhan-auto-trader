package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdxDmiIndexExitRulesTest {
    static final LocalDateTime START=LocalDateTime.of(2026,11,11,10,0);
    @Test void threeIndependentBuyRequestsKeepPremiumEntryAndDirectionalIndexLevels() {
        for(int direction:List.of(1,-1)) {
            var signal=new AdxDmiRules.Signal(direction,START,100, direction==1?90:110,new AdxDmiRules.Reading(30,10,30));
            // Premium need not cover delta*indexRisk: there is no fixed premium SL in this policy.
            var plan=AdxDmiIndexPlan.create(1.01,100,signal,.05);
            var r=new StrategyApplyRequest();r.setSymbol("RELIANCE");r.setUserId(1L);r.setBrokerCredentialsId(2L);
            var orders=AdxDmiStrategy.orders(r,signal,AdxDmiStrategyTest.option(12,signal.side(),"17/11/2026",100),44,plan,AdxDmiGreeks.Reading.fallback("TEST"));
            assertEquals(3,orders.size());
            assertEquals(direction==1?List.of(110d,120d,130d):List.of(90d,80d,70d),orders.stream().map(TriggerRequest::getTarget1).toList());
            for(var order:orders) {
                assertEquals("BUY",order.getAction());assertEquals(1,order.getLots());assertEquals(1,order.getQuantity());
                assertEquals(1.05,order.getEntryPrice());assertEquals(signal.stop(),order.getStopLoss());
                assertTrue(order.getUseSpotForSl());assertTrue(order.getUseSpotForTarget());assertFalse(order.getUseSpotForEntry());
                assertFalse(order.getTslEnabled());assertNull(order.getTarget2());assertNull(order.getTarget3());
                assertEquals("INDEX_REQUEST_V2",AdxDmiEntryAdmission.field(order.getStrategyDecision(),"ADX_EXIT"));
                var settings=order.getExecutionSettings();assertNotNull(settings);
                assertTrue(settings.getMarketableEntry());assertFalse(settings.getRequireDisplayedDepth());
                assertEquals(15,settings.getTimeExitMinutes());assertEquals(plan.t1(),settings.getTimeExitProgressPrice());
                assertEquals(direction==1?org.com.sharekhan.execution.TradeExecutionSettings.Direction.ABOVE:org.com.sharekhan.execution.TradeExecutionSettings.Direction.BELOW,settings.getTimeExitProgressDirection());
                assertEquals(orders.getFirst().getExecutionSettings().getTimeExitGroupId(),settings.getTimeExitGroupId());
                assertTrue(settings.getSpotStopOnTouch());assertFalse(settings.getRequireProfitableSpotTarget());
            }
        }
    }
    @Test void t1BeforeDeadlineSurvivesRetracementAndNeverMovesOriginalStop() {
        var state=new AdxDmiIndexExitRules.State(START,null,null);
        state=AdxDmiIndexExitRules.observe(state,false,110,110d,START.plusMinutes(14));
        state=AdxDmiIndexExitRules.observe(state,false,110,100d,START.plusHours(1));
        assertNotNull(state.t1ReachedAt());assertNull(state.timedOutAt());
        assertNull(AdxDmiIndexExitRules.exit(state,false,90,120,100d,START.plusHours(1)));
        assertEquals(AdxDmiIndexExitRules.STOP,AdxDmiIndexExitRules.exit(state,false,90,120,90d,START.plusHours(1)));
    }
    @Test void missingSpotDoesNotPauseDeadlineAndLateT1CannotReverseTimeout() {
        var state=new AdxDmiIndexExitRules.State(START,null,null);
        assertNull(AdxDmiIndexExitRules.observe(state,false,110,null,START.plusMinutes(14)).timedOutAt());
        state=AdxDmiIndexExitRules.observe(state,false,110,110d,START.plusMinutes(15));
        assertNull(state.t1ReachedAt());assertNotNull(state.timedOutAt());
        state=AdxDmiIndexExitRules.observe(state,false,110,130d,START.plusMinutes(16));
        assertEquals(AdxDmiIndexExitRules.TIMEOUT,AdxDmiIndexExitRules.exit(state,false,90,130,null,START.plusMinutes(16)));
    }
    @Test void peTriggersAreInvertedAndSessionExitNeedsNoSpot() {
        var state=AdxDmiIndexExitRules.observe(new AdxDmiIndexExitRules.State(START,null,null),true,90,89d,START.plusMinutes(1));
        assertEquals(AdxDmiIndexExitRules.TARGET,AdxDmiIndexExitRules.exit(state,true,110,80,79d,START.plusMinutes(2)));
        assertEquals(AdxDmiIndexExitRules.STOP,AdxDmiIndexExitRules.exit(state,true,110,80,111d,START.plusMinutes(2)));
        assertEquals("INTRADAY_CLOSE",AdxDmiIndexExitRules.exit(state,true,110,80,null,START.withHour(15).withMinute(20)));
        assertNull(AdxDmiIndexExitRules.exit(state,true,110,80,Double.NaN,START.plusMinutes(2)));
    }
    @Test void oldPremiumTradesAndOtherSourcesDoNotChangePolicy() {
        var t=TriggeredTradeSetupEntity.builder().source(AdxDmiRules.PREMIUM_SOURCE).build();
        assertFalse(AdxDmiIndexExitRules.owns(t));t.setComment("ADX_EXIT="+AdxDmiIndexExitRules.VERSION+";");
        assertTrue(AdxDmiIndexExitRules.owns(t));t.setSource(AdxDmiRules.SOURCE);assertFalse(AdxDmiIndexExitRules.owns(t));
    }
    @Test void exactTickEqualityIsNotLostToBinaryFloatingPointRounding() {
        var state=AdxDmiIndexExitRules.observe(new AdxDmiIndexExitRules.State(START,null,null),false,23062.100000000002,23062.1,START.plusMinutes(1));
        assertNotNull(state.t1ReachedAt());
        assertEquals(AdxDmiIndexExitRules.TARGET,AdxDmiIndexExitRules.exit(state,false,23019,23062.100000000002,23062.1,START.plusMinutes(1)));
        assertNull(AdxDmiIndexExitRules.exit(state,false,23019,23062.100000000002,23062.05,START.plusMinutes(1)));
    }
}
