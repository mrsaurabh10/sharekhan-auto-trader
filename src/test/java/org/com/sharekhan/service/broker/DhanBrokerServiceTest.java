package org.com.sharekhan.service.broker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.dhan.*;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DhanBrokerServiceTest {
    DhanOrderClient client=mock(DhanOrderClient.class);
    DhanInstrumentResolver instruments=mock(DhanInstrumentResolver.class);
    DhanSubmissionClaim claims=mock(DhanSubmissionClaim.class);
    BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);
    AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);
    CryptoService crypto=mock(CryptoService.class);
    DhanBrokerService service=new DhanBrokerService(client,instruments,claims,credentials,tokens,crypto);
    BrokerContext context=new BrokerContext(null,null,"1000000001","Dhan",7L);
    TriggeredTradeSetupEntity trade;
    @BeforeEach void setup() {
        trade=TriggeredTradeSetupEntity.builder().id(12L).appUserId(4L).brokerCredentialsId(7L).symbol("TCS").exchange("NC").quantity(10L).intraday(true).build();
        when(credentials.findById(7L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(7L).appUserId(4L).brokerName("Dhan").clientCode("encrypted-client").active(true).tradingEnabled(true).build()));
        when(crypto.decrypt("encrypted-client")).thenReturn("1000000001");
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("test-token").expiry(Instant.now().plusSeconds(3600)).build()));
        when(instruments.resolve(trade)).thenReturn(new DhanInstrumentResolver.Instrument("NSE_EQ","11536",1,new BigDecimal("0.05"),10000));
    }
    DhanOrderClient.Response response(int code,JSONObject body) { return new DhanOrderClient.Response(code,body.toString()); }
    JSONObject order(String status,long filled,long remaining) {
        return new JSONObject().put("dhanClientId","1000000001").put("orderId","12345").put("orderStatus",status)
            .put("transactionType","BUY").put("exchangeSegment","NSE_EQ").put("securityId","11536")
            .put("orderType","LIMIT").put("quantity",10).put("filledQty",filled).put("remainingQuantity",remaining).put("averageTradedPrice",100.5);
    }
    @Test void placesScopedCashLimitWithUnitsAndNeverConfirmsAcknowledgementAsFill() throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),eq("test-token"),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","TRADED")));
        var result=service.placeOrder(trade,context,100.5);
        assertThat(result.isSuccess()).isTrue();assertThat(result.getStatus()).isEqualTo("Pending");assertThat(result.getExecutedPrice()).isNull();
        var body=ArgumentCaptor.forClass(JSONObject.class);verify(client).exchange(eq("POST"),eq("/orders"),eq("test-token"),body.capture());
        assertThat(body.getValue().getString("dhanClientId")).isEqualTo("1000000001");
        assertThat(body.getValue().getString("securityId")).isEqualTo("11536");
        assertThat(body.getValue().getLong("quantity")).isEqualTo(10);
        assertThat(body.getValue().getString("transactionType")).isEqualTo("BUY");
        assertThat(body.getValue().getString("productType")).isEqualTo("INTRADAY");
        var ordered=inOrder(claims,client);ordered.verify(claims).claim(anyString(),eq(7L),eq("TRADE:12"),eq("BUY"));ordered.verify(client).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void entryCanUseDurableRequestBeforeTradeRowExistsAndReentryHasDistinctScope() throws Exception {
        trade.setId(null);trade.setTriggerRequestId(77L);
        when(client.exchange(eq("POST"),anyString(),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue();
        verify(claims).claim(anyString(),eq(7L),eq("REQUEST:77:REENTRY:0"),eq("BUY"));
        trade.setGapReentryCount(1);assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue();
        verify(claims).claim(anyString(),eq(7L),eq("REQUEST:77:REENTRY:1"),eq("BUY"));
    }
    @Test void disabledNewTradingStillAllowsExistingExit() throws Exception {
        var broker=credentials.findById(7L).orElseThrow();broker.setTradingEnabled(false);
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        when(client.exchange(eq("POST"),anyString(),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isTrue();
        var body=ArgumentCaptor.forClass(JSONObject.class);verify(client).exchange(eq("POST"),eq("/orders"),anyString(),body.capture());
        assertThat(body.getValue().getString("transactionType")).isEqualTo("SELL");
    }
    @Test void optionStopLimitUsesMarginAndNeverMultipliesUnitsAgain() throws Exception {
        trade.setExchange("NF");trade.setQuantity(50L);trade.setIntraday(false);
        when(instruments.resolve(trade)).thenReturn(new DhanInstrumentResolver.Instrument("NSE_FNO","999",25,new BigDecimal("0.05"),1000));
        when(client.exchange(eq("POST"),anyString(),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.placeTriggerPriceEntryOrder(trade,context,100,100.5).isSuccess()).isTrue();
        var body=ArgumentCaptor.forClass(JSONObject.class);verify(client).exchange(eq("POST"),eq("/orders"),anyString(),body.capture());
        assertThat(body.getValue().getLong("quantity")).isEqualTo(50);assertThat(body.getValue().getString("orderType")).isEqualTo("STOP_LOSS");
        assertThat(body.getValue().getString("productType")).isEqualTo("MARGIN");
        assertThat(body.getValue().getDouble("triggerPrice")).isEqualTo(100);
    }
    @Test void blocksInvalidTicksLotsFreezeAndProductsBeforeClaimOrHttp() {
        assertThat(service.placeOrder(trade,context,100.03).isSuccess()).isFalse();
        trade.setQuantity(10001L);assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        trade.setQuantity(26L);when(instruments.resolve(trade)).thenReturn(new DhanInstrumentResolver.Instrument("NSE_FNO","999",25,new BigDecimal("0.05"),1000));
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        trade.setQuantity(25L);trade.setBrokerProductType("BIGTRADEPLUS");assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        verifyNoInteractions(client,claims);
    }
    @Test void noCrossUserExpiredTokenOrUnscopedFallback() {
        trade.setAppUserId(88L);assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        trade.setAppUserId(4L);when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.empty());
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        context.setBrokerCredentialsId(null);assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        verifyNoInteractions(client,claims);
    }
    @Test void timeoutReturnsCorrelationHandleAndExistingClaimNeverReposts() throws Exception {
        when(client.exchange(anyString(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("secret-response"));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isTrue();assertThat(result.getOrderId()).startsWith("DHAN-CORR-");
        assertThat(result.getRejectionReason()).doesNotContain("secret-response");
        doThrow(new DuplicateKeyException("duplicate")).when(claims).claim(anyString(),anyLong(),anyString(),anyString());
        var retry=service.placeOrder(trade,context,100);
        assertThat(retry.getOrderId()).isEqualTo(result.getOrderId());
        verify(client,times(1)).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void recoversLostAcknowledgementByMatchingCorrelationWithoutResend() throws Exception {
        when(client.exchange(eq("POST"),anyString(),anyString(),any())).thenThrow(new IllegalStateException());
        when(client.exchange(eq("GET"),anyString(),anyString(),isNull())).thenAnswer(call->{String path=call.getArgument(1);return response(200,order("PENDING",0,10).put("correlationId",path.substring(path.lastIndexOf('/')+1)));});
        assertThat(service.placeOrder(trade,context,100).getOrderId()).isEqualTo("12345");
        verify(client,times(1)).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void explicitHttpRejectionIsTerminalAndDoesNotReconcileOrRetry() throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(new DhanOrderClient.Response(400,"private broker response"));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isDefinitiveBrokerRejection()).isTrue();
        assertThat(result.getStatus()).isEqualTo("Rejected");
        assertThat(result.getRejectionReason()).contains("HTTP 400").doesNotContain("private broker response");
        verify(client,never()).exchange(eq("GET"),anyString(),anyString(),any());
    }
    @Test void rejectedAcknowledgementBecomesTerminalWhenOrderLookupConfirmsZeroFills() throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","REJECTED")));
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull()))
            .thenReturn(response(200,order("REJECTED",0,10)));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isDefinitiveBrokerRejection()).isTrue();
        assertThat(result.getOrderId()).isEqualTo("12345");
        assertThat(result.getStatus()).isEqualTo("Rejected");
    }
    @Test void rejectedAcknowledgementWithUnavailableLookupRetainsTrackingId() throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","REJECTED")));
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull()))
            .thenReturn(new DhanOrderClient.Response(503,"unavailable"));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDefinitiveBrokerRejection()).isFalse();
        assertThat(result.getOrderId()).isEqualTo("12345");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={408,429,500})
    void uncertainHttpResponseRemainsPendingForCorrelationRecovery(int http) throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(new DhanOrderClient.Response(http,"unavailable"));
        when(client.exchange(eq("GET"),anyString(),anyString(),isNull()))
            .thenReturn(new DhanOrderClient.Response(404,"not found"));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDefinitiveBrokerRejection()).isFalse();
        assertThat(result.getOrderId()).startsWith("DHAN-CORR-");
    }
    @Test void rejectedAcknowledgementWithPartialFillStillRequiresReconciliation() throws Exception {
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any()))
            .thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","REJECTED")));
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull()))
            .thenReturn(response(200,order("REJECTED",3,7)));
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDefinitiveBrokerRejection()).isFalse();
        assertThat(result.getOrderId()).isEqualTo("12345");
        assertThat(result.getStatus()).isEqualTo("Pending");
    }
    @Test void mapsPartialAndTerminalFillsUsingActualAverageAndRejectsContradictions() throws Exception {
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(response(200,order("PART_TRADED",3,7)),response(200,order("TRADED",10,0)),response(200,order("PENDING",10,0)),response(200,order("TRADED",10,0).put("averageTradedPrice",0)));
        assertThat(service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0).getString("orderStatus")).isEqualTo("Partly Executed");
        var full=service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0);
        assertThat(full.getString("orderStatus")).isEqualTo("Fully Executed");assertThat(full.getDouble("avgPrice")).isEqualTo(100.5);
        assertThat(service.fetchOrderStatus(trade,context,"12345")).isNull();assertThat(service.fetchOrderStatus(trade,context,"12345")).isNull();
    }
    @Test void arrayOrderHistoryConfirmsFillAndMatchesIdentityRatherThanFirstRow() throws Exception {
        var rows = new org.json.JSONArray().put(order("PENDING",0,10).put("orderId","unrelated"))
            .put(order("TRADED",10,0));
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull()))
            .thenReturn(new DhanOrderClient.Response(200,rows.toString()));
        var full=service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0);
        assertThat(full.getString("orderStatus")).isEqualTo("Fully Executed");
        assertThat(full.getDouble("avgPrice")).isEqualTo(100.5);
        verify(client,times(1)).exchange(eq("GET"),eq("/orders/12345"),eq("test-token"),isNull());
        verify(client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void arrayCorrelationRecoveryRetainsExactAccountAndOrder() throws Exception {
        var rows=new org.json.JSONArray().put(order("TRADED",10,0).put("correlationId","sat-test"));
        when(client.exchange(eq("GET"),eq("/orders/external/sat-test"),anyString(),isNull()))
            .thenReturn(new DhanOrderClient.Response(200,rows.toString()));
        var full=service.fetchOrderStatus(trade,context,"DHAN-CORR-sat-test").getJSONArray("data").getJSONObject(0);
        assertThat(full.getString("orderId")).isEqualTo("DHAN-CORR-sat-test");
        assertThat(full.getString("orderStatus")).isEqualTo("Fully Executed");
    }
    @Test void invalidOrAmbiguousArraysNeverConfirmExecution() throws Exception {
        var full=order("TRADED",10,0);
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(
            new DhanOrderClient.Response(200,"[]"),
            new DhanOrderClient.Response(200,new org.json.JSONArray().put(order("TRADED",10,0).put("orderId","different")).toString()),
            new DhanOrderClient.Response(200,new org.json.JSONArray().put(order("TRADED",10,0).put("dhanClientId","foreign")).toString()),
            new DhanOrderClient.Response(200,new org.json.JSONArray().put(full).put(full).toString()),
            new DhanOrderClient.Response(200,"[null]"),
            new DhanOrderClient.Response(200,new org.json.JSONArray().put(full).toString()+" trailing"));
        for(int i=0;i<6;i++) assertThat(service.fetchOrderStatus(trade,context,"12345")).isNull();
    }
    @Test void arrayPartialFillRemainsPartialAndDeliveryCashUsesCnc() throws Exception {
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull()))
            .thenReturn(new DhanOrderClient.Response(200,new org.json.JSONArray().put(order("PART_TRADED",3,7)).toString()));
        assertThat(service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0).getString("orderStatus")).isEqualTo("Partly Executed");
        trade.setIntraday(false);
        when(client.exchange(eq("POST"),eq("/orders"),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue();
        var body=ArgumentCaptor.forClass(JSONObject.class);
        verify(client).exchange(eq("POST"),eq("/orders"),anyString(),body.capture());
        assertThat(body.getValue().getString("productType")).isEqualTo("CNC");
    }
    @Test void modificationUsesOriginalTotalQuantityAndRequiresSameOrderAcknowledgement() throws Exception {
        trade.setExitOrderId("12345");
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(response(200,order("PART_TRADED",3,7).put("transactionType","SELL")));
        when(client.exchange(eq("PUT"),eq("/orders/12345"),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","different").put("orderStatus","PENDING")),response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.modifyEntryOrder(trade,context,"12345",100).isSuccess()).isFalse();
        assertThat(service.modifyEntryOrder(trade,context,"12345",100).isSuccess()).isTrue();
        var body=ArgumentCaptor.forClass(JSONObject.class);verify(client,times(2)).exchange(eq("PUT"),eq("/orders/12345"),anyString(),body.capture());
        assertThat(body.getValue().getLong("quantity")).isEqualTo(10);
        assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isFalse();
    }
    @Test void cancellationAckIsNotFillAndFailedCancelIsSanitized() throws Exception {
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(response(200,order("PENDING",0,10)));
        when(client.exchange(eq("DELETE"),eq("/orders/12345"),anyString(),isNull())).thenReturn(new DhanOrderClient.Response(202,""),new DhanOrderClient.Response(500,"private-error"));
        service.cancelEntryOrder(trade,context,"12345");
        assertThatThrownBy(()->service.cancelEntryOrder(trade,context,"12345")).hasMessageContaining("unconfirmed").hasMessageNotContaining("private-error").hasNoCause();
        verify(client,never()).exchange(eq("POST"),anyString(),anyString(),any());
    }
    @Test void streamNormalizationChecksCurrentOwnerAccountOrderAndSideWithoutRest() {
        assertThat(service.normalizeOrderUpdate(trade,context,"12345",order("TRADED",10,0))).isNotNull();
        assertThat(service.normalizeOrderUpdate(trade,context,"other-order",order("TRADED",10,0))).isNull();
        assertThat(service.normalizeOrderUpdate(trade,context,"12345",order("TRADED",10,0).put("dhanClientId","foreign-client"))).isNull();
        assertThat(service.normalizeOrderUpdate(trade,context,"12345",order("TRADED",10,0).put("transactionType","SELL"))).isNull();
        verifyNoInteractions(client);
    }
    @Test void partialCancelledExitStaysPendingToPreventOverselling() throws Exception {
        trade.setExitOrderId("12345");
        when(client.exchange(eq("GET"),anyString(),anyString(),isNull())).thenReturn(response(200,order("CANCELLED",3,7).put("transactionType","SELL")));
        assertThat(service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0).getString("orderStatus")).isEqualTo("Partly Executed");
    }
    @Test void chaseReusesPrecedingStatusReadForOneModificationOnly() throws Exception {
        trade.setOrderId("12345");
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(response(200,order("PENDING",0,10)));
        when(client.exchange(eq("PUT"),eq("/orders/12345"),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        assertThat(service.fetchOrderStatus(trade,context,"12345")).isNotNull();
        assertThat(service.modifyEntryOrder(trade,context,"12345",100).isSuccess()).isTrue();
        verify(client,times(1)).exchange(eq("GET"),anyString(),anyString(),isNull());
        assertThat(service.modifyEntryOrder(trade,context,"12345",100.05).isSuccess()).isTrue();
        verify(client,times(2)).exchange(eq("GET"),anyString(),anyString(),isNull());
    }
    @Test void staleMetadataAndTokenRotationRequireFreshStatusRead() throws Exception {
        trade.setOrderId("12345");var nanos=new java.util.concurrent.atomic.AtomicLong();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"snapshotTicker",(java.util.function.LongSupplier)nanos::get);
        when(client.exchange(eq("GET"),eq("/orders/12345"),anyString(),isNull())).thenReturn(response(200,order("PENDING",0,10)));
        when(client.exchange(eq("PUT"),eq("/orders/12345"),anyString(),any())).thenReturn(response(200,new JSONObject().put("orderId","12345").put("orderStatus","PENDING")));
        service.fetchOrderStatus(trade,context,"12345");nanos.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(3));
        service.modifyEntryOrder(trade,context,"12345",100);verify(client,times(2)).exchange(eq("GET"),anyString(),anyString(),isNull());
        service.fetchOrderStatus(trade,context,"12345");
        when(tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",7L)).thenReturn(Optional.of(AccessTokenEntity.builder().token("rotated").expiry(Instant.now().plusSeconds(3600)).build()));
        service.modifyEntryOrder(trade,context,"12345",100);
        verify(client).exchange(eq("GET"),eq("/orders/12345"),eq("rotated"),isNull());
    }

}
