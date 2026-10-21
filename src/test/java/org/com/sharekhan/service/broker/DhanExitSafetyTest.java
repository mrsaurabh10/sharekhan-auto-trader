package org.com.sharekhan.service.broker;

import org.junit.jupiter.api.*;
import org.com.sharekhan.dhan.*;
import org.json.*;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DhanExitSafetyTest {
    DhanBrokerServiceTest f=new DhanBrokerServiceTest();
    @BeforeEach void setup() throws Exception {f.setup();}
    JSONObject sell(String status,long filled,long remaining){return f.order(status,filled,remaining).put("transactionType","SELL");}
    void prior(int generation,String outcome) {
        when(f.claims.latestExit(7L,12L)).thenReturn(Optional.of(new DhanSubmissionClaim.ExitAttempt("sat-prior","TRADE:12:EXIT:"+generation,generation,outcome)));
    }
    void ack() throws Exception {
        when(f.client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(f.response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
    }
    @Test void definitiveRefusalAdvancesGenerationWithoutReusingOldKey() throws Exception {
        prior(1,"REFUSED");ack();
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isTrue();
        verify(f.claims).claimExit(anyString(),eq(7L),eq("TRADE:12:EXIT:2"),eq(2));
        verify(f.client,never()).exchange(eq("GET"),contains("external"),anyString(),any());
    }
    @Test void oldLegacyCancelledZeroFillAllowsExactlyOneNewGeneration() throws Exception {
        prior(0,null);ack();
        when(f.client.exchange(eq("GET"),eq("/orders/external/sat-prior"),anyString(),isNull()))
            .thenReturn(f.response(200,sell("CANCELLED",0,10).put("correlationId","sat-prior")));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isTrue();
        verify(f.claims).exitOutcome("sat-prior","INACTIVE_ZERO_FILL");
        verify(f.claims).claimExit(anyString(),eq(7L),eq("TRADE:12:EXIT:1"),eq(1));
        verify(f.client,times(1)).exchange(eq("POST"),eq("/orders"),anyString(),any());
    }
    @Test void unavailablePriorCorrelationNeverAuthorizesReplacement() throws Exception {
        prior(1,null);
        when(f.client.exchange(eq("GET"),eq("/orders/external/sat-prior"),anyString(),isNull())).thenReturn(new DhanOrderClient.Response(404,"missing"));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).getOrderId()).isEqualTo("DHAN-CORR-sat-prior");
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
        verify(f.claims,never()).claimExit(anyString(),anyLong(),anyString(),anyInt());
    }
    @Test void partiallyFilledInactivePriorRetainsOrderAndExposure() throws Exception {
        prior(1,null);
        when(f.client.exchange(eq("GET"),eq("/orders/external/sat-prior"),anyString(),isNull()))
            .thenReturn(f.response(200,sell("CANCELLED",3,7).put("correlationId","sat-prior")));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).getOrderId()).isEqualTo("12345");
        verify(f.claims,never()).exitOutcome(anyString(),anyString());
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void filledPriorDoesNotSellAgain() throws Exception {
        prior(1,null);
        when(f.client.exchange(eq("GET"),eq("/orders/external/sat-prior"),anyString(),isNull()))
            .thenReturn(f.response(200,sell("TRADED",10,0).put("correlationId","sat-prior")));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).getOrderId()).isEqualTo("12345");
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void httpRefusalStoresOutcomeAndOnlySafeBrokerCode() throws Exception {
        when(f.client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(new DhanOrderClient.Response(400,"{\"errorCode\":\"DH-906\",\"errorMessage\":\"test-token PIN 123456\"}"));
        var result=f.service.placeExitOrder(f.trade,f.context,100);
        assertThat(result.isSuccess()).isFalse();assertThat(result.getRejectionReason()).contains("DH-906").doesNotContain("test-token","123456");
        verify(f.claims).exitOutcome(anyString(),eq("REFUSED"));
    }
    @Test void wrongContractQuantityAndProductNeverConfirmRestOrStreamFill() throws Exception {
        f.trade.setExitOrderId("12345");f.trade.setBrokerExecutionProduct("CNC");
        for(var row:new JSONObject[]{sell("TRADED",10,0).put("securityId","WRONG"),sell("TRADED",1,0).put("quantity",1),sell("TRADED",10,0).put("productType","INTRADAY")}) {
            when(f.client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(f.response(200,row));
            assertThat(f.service.fetchOrderStatus(f.trade,f.context,"12345")).isNull();
            assertThat(f.service.normalizeOrderUpdate(f.trade,f.context,"12345",row)).isNull();
        }
    }
    @Test void persistedContractIdentityAllowsReadsWithoutTodaysMaster() throws Exception {
        f.trade.setBrokerSecurityId("11536");
        when(f.instruments.resolve(f.trade)).thenThrow(new IllegalStateException("master down"));
        when(f.client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(f.response(200,f.order("TRADED",10,0)));
        assertThat(f.service.fetchOrderStatus(f.trade,f.context,"12345")).isNotNull();
    }
    @Test void legacyProductComesFromVerifiedPositionRatherThanAppFlag() throws Exception {
        f.trade.setIntraday(false);ack();
        JSONObject row=new JSONObject().put("dhanClientId","1000000001").put("securityId","11536").put("exchangeSegment","NSE_EQ")
            .put("productType","INTRADAY").put("netQty",10).put("positionType","LONG");
        when(f.client.exchange(eq("GET"),eq("/positions"),anyString(),isNull())).thenReturn(new DhanOrderClient.Response(200,new JSONArray().put(row).toString()));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isTrue();
        var body=ArgumentCaptor.forClass(JSONObject.class);verify(f.client).exchange(eq("POST"),eq("/orders"),anyString(),body.capture());
        assertThat(body.getValue().getString("productType")).isEqualTo("INTRADAY");assertThat(f.trade.getIntraday()).isFalse();
    }
    @Test void closedPositionAndReservedExposureBlockNewSell() throws Exception {
        when(f.client.exchange(eq("GET"),eq("/positions"),anyString(),isNull())).thenReturn(new DhanOrderClient.Response(200,"[]"));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isFalse();
        verify(f.claims,never()).claimExit(anyString(),anyLong(),anyString(),anyInt());
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void anotherPendingSellReservesPosition() throws Exception {
        when(f.client.exchange(eq("GET"),eq("/orders"),anyString(),isNull())).thenReturn(new DhanOrderClient.Response(200,new JSONArray().put(sell("PENDING",0,10).put("productType","INTRADAY")).toString()));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isFalse();
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void directRepricingRejectsChangedBrokerQuantity() throws Exception {
        f.trade.setExitOrderId("12345");
        when(f.client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(f.response(200,sell("PENDING",0,1).put("quantity",1)));
        assertThat(f.service.modifyEntryOrder(f.trade,f.context,"12345",100).isSuccess()).isFalse();
        verify(f.client,never()).exchange(eq("PUT"),anyString(),anyString(),any());
    }
    @Test void missingExitIdCanResetOnlyWithCurrentDurableRefusal() {
        prior(1,null);assertThat(f.service.hasDefinitivelyRefusedExit(f.trade,f.context)).isFalse();
        prior(1,"REFUSED");assertThat(f.service.hasDefinitivelyRefusedExit(f.trade,f.context)).isTrue();
        f.trade.setAppUserId(999L);assertThat(f.service.hasDefinitivelyRefusedExit(f.trade,f.context)).isFalse();
    }

    @Test void mutatedTradeContractCannotOverwritePersistedBrokerIdentity() {
        f.trade.setBrokerSecurityId("ORIGINAL");
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isFalse();
        assertThat(f.trade.getBrokerSecurityId()).isEqualTo("ORIGINAL");
        verifyNoInteractions(f.client,f.claims);
    }
    @Test void wrongSideOnPriorCorrelationCannotAuthorizeNewGeneration() throws Exception {
        prior(1,null);
        when(f.client.exchange(eq("GET"),eq("/orders/external/sat-prior"),anyString(),isNull()))
            .thenReturn(f.response(200,f.order("CANCELLED",0,10).put("correlationId","sat-prior")));
        assertThat(f.service.placeExitOrder(f.trade,f.context,100).isSuccess()).isFalse();
        verify(f.claims,never()).exitOutcome(anyString(),anyString());
        verify(f.client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }

}
