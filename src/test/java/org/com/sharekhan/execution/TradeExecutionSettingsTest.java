package org.com.sharekhan.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.com.sharekhan.dto.TriggerRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeExecutionSettingsTest {
    @Test void optionalSettingsDefaultToExistingBehaviorAndCopyIndependently() {
        assertNull(TradeExecutionSettings.prepare(null,null));
        var c=new TradeExecutionSettings();c.setTimeExitMinutes(15);
        var prepared=TradeExecutionSettings.prepare(c,null);assertNotNull(prepared.getTimeExitGroupId());assertNull(c.getTimeExitGroupId());
        assertNull(prepared.getSpotStopOnTouch());assertNull(prepared.getRequireProfitableSpotTarget());
        var other=prepared.copy();other.setTimeExitMinutes(30);assertEquals(15,prepared.getTimeExitMinutes());
    }
    @Test void validatesDurationAndProgressPairAndSpotReference() {
        for(int n:new int[]{0,-1,1441}){var c=new TradeExecutionSettings();c.setTimeExitMinutes(n);assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,null));}
        var c=new TradeExecutionSettings();c.setTimeExitProgressPrice(110d);assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,null));
        c.setTimeExitMinutes(15);assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,null));
        c.setTimeExitProgressDirection(TradeExecutionSettings.Direction.ABOVE);c.setTimeExitUseSpot(true);
        assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,null));assertNotNull(TradeExecutionSettings.prepare(c,123));
        for(double p:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}){c.setTimeExitProgressPrice(p);assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,123));}
    }
    @Test void nativeBracketCannotAcquireASecondLocalExitOwner() {
        var c=new TradeExecutionSettings();c.setTimeExitMinutes(15);
        assertThrows(IllegalArgumentException.class,()->TradeExecutionSettings.prepare(c,null,"BIGTRADEPLUS"));
    }
    @Test void requestApiAcceptsGenericTimerButCannotSetServerOwnedGroup() throws Exception {
        var mapper=new ObjectMapper();
        var r=mapper.readValue("{\"executionSettings\":{\"timeExitMinutes\":15,\"timeExitProgressPrice\":23020,\"timeExitProgressDirection\":\"ABOVE\",\"timeExitUseSpot\":true,\"timeExitGroupId\":\"forged\"}}",TriggerRequest.class);
        assertEquals(15,r.getExecutionSettings().getTimeExitMinutes());assertNull(r.getExecutionSettings().getTimeExitGroupId());
        r.getExecutionSettings().setTimeExitGroupId("server");assertFalse(mapper.writeValueAsString(r).contains("server"));
    }
}
