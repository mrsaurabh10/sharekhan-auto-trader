package org.com.sharekhan.strategy.adxdmi;
import org.com.sharekhan.dto.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.*;
class AdxDmiPremiumPlanTest {
    @Test void deltaSetsEqualTickAlignedRiskAndFrozenTargetsForBothOptionSides(){
        var plan=AdxDmiPremiumPlan.create(100.01,20,.543,.05);
        assertEquals(100.05,plan.entry());assertEquals(10.9,plan.risk());assertEquals(89.15,plan.stop());
        assertEquals(110.95,plan.t1());assertEquals(121.85,plan.t2());assertEquals(132.75,plan.t3());
        for(int direction:new int[]{1,-1}){
            var r=new StrategyApplyRequest();r.setSymbol("RELIANCE");r.setUserId(1L);r.setBrokerCredentialsId(2L);
            var signal=new AdxDmiRules.Signal(direction,LocalDateTime.of(2026,11,11,10,0),1400,1380,new AdxDmiRules.Reading(30,10,30));
            var requests=AdxDmiStrategy.orders(r,signal,AdxDmiStrategyTest.option(12,signal.side(),"17/11/2026",1400),44,plan,AdxDmiGreeks.Reading.fallback("TEST"));
            assertEquals(3,requests.size());
            assertEquals(java.util.List.of(plan.t1(),plan.t2(),plan.t3()),requests.stream().map(TriggerRequest::getTarget1).toList());
            for(var request:requests){
                assertEquals("BUY",request.getAction());assertEquals(signal.side(),request.getOptionType());assertEquals(1,request.getQuantity());
                assertEquals(2L,request.getBrokerCredentialsId());assertEquals(plan.entry(),request.getEntryPrice());assertEquals(plan.stop(),request.getStopLoss());
                assertFalse(request.getTslEnabled());assertFalse(request.getUseSpotForSl());assertNull(request.getTarget2());assertNull(request.getTarget3());
            }
        }
    }
    @Test void invalidOrNonPositiveStopCannotCreateOrders(){
        assertThrows(IllegalArgumentException.class,()->AdxDmiPremiumPlan.create(1,10,.5,.05));
        assertThrows(IllegalArgumentException.class,()->AdxDmiPremiumPlan.create(100,10,Double.NaN,.05));
        assertThrows(IllegalArgumentException.class,()->AdxDmiPremiumPlan.create(100,-10,.5,.05));
    }
    @Test void exactDhanContractUsesAbsoluteSignedDeltaAndMissingOrWrongContractFallsBack(){
        var option=AdxDmiStrategyTest.option(12,"PE","17/11/2026",25000);var at=Instant.now();
        var data=new JSONObject("{\"status\":\"success\",\"data\":{\"oc\":{\"25000.000000\":{\"pe\":{\"security_id\":123,\"greeks\":{\"delta\":-0.44,\"gamma\":0.001,\"vega\":3,\"theta\":-2}}}}}}");
        var reading=AdxDmiGreeks.parse(data,option,"123",at,at);
        assertEquals(.44,reading.delta());assertEquals("DHAN_API",reading.source());assertEquals(.001,reading.gamma());
        assertEquals(.5,AdxDmiGreeks.parse(data,option,"124",at,at).delta());
        assertEquals(.5,AdxDmiGreeks.parse(data,option,"123",at.minusSeconds(6),at).delta());
        assertEquals(.5,AdxDmiGreeks.parse(new JSONObject(),option,"123",at,at).delta());
        option.setOptionType("CE");assertEquals(.5,AdxDmiGreeks.parse(data,option,"123",at,at).delta());
    }
    @Test void admissionRejectsLateAndPreviousDaySignals(){
        String metadata="ADX_SIGNAL=2026-11-11T09:25:CE;ADX_DEADLINE=2026-11-11T09:30;";
        assertDoesNotThrow(()->AdxDmiEntryAdmission.deadline(metadata,LocalDateTime.of(2026,11,11,9,25)));
        assertThrows(IllegalStateException.class,()->AdxDmiEntryAdmission.deadline(metadata,LocalDateTime.of(2026,11,11,9,30)));
        assertThrows(IllegalStateException.class,()->AdxDmiEntryAdmission.deadline(metadata,LocalDateTime.of(2026,11,12,9,25)));
        assertFalse(AdxDmiEntryAdmission.entryWindow(LocalDateTime.of(2026,11,11,12,0)));
    }
}
