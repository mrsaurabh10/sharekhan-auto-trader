package org.com.sharekhan.service.broker;

import java.util.*;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.mstock.MStockSubmissionStore;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MStockBrokerServiceTest {
    TokenStoreService tokens = mock(TokenStoreService.class);
    CryptoService crypto = mock(CryptoService.class);
    MStockInstrumentResolver instruments = mock(MStockInstrumentResolver.class);
    MStockInstrumentRepository master = mock(MStockInstrumentRepository.class);
    BrokerCredentialsRepository accounts = mock(BrokerCredentialsRepository.class);
    MStockSubmissionStore submissions = mock(MStockSubmissionStore.class);
    MStockBrokerService service = spy(new MStockBrokerService(tokens,crypto,instruments,master,accounts,submissions));
    BrokerContext context = new BrokerContext(null, "untrusted-key", null, "MStock", 7L);
    TriggeredTradeSetupEntity trade;
    BrokerCredentialsEntity account;
    MStockInstrumentEntity instrument;
    @BeforeEach void setup() throws Exception {
        doReturn("regular").when(service).orderVariety();
        trade = TriggeredTradeSetupEntity.builder().id(88L).triggerRequestId(99L).appUserId(2L).brokerCredentialsId(7L)
                .scripCode(42).exchange("NF").symbol("TEST").quantity(50L).intraday(true).build();
        account = BrokerCredentialsEntity.builder().id(7L).appUserId(2L).brokerName("MStock").active(true).tradingEnabled(true).apiKey("cipher").build();
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(tokens.getSavedTokenInfoForBrokerCredentials(Broker.MSTOCK,7L))
                .thenReturn(new TokenStoreService.TokenInfo("access","cipher",null,2L,7L));
        when(crypto.decrypt("cipher")).thenReturn("real-key");
        when(instruments.resolveInstrumentKey(42)).thenReturn(Optional.of("NFO:TEST26NOV100CE"));
        instrument = MStockInstrumentEntity.builder().instrumentKey("NFO:TEST26NOV100CE").tradingSymbol("TEST26NOV100CE")
                .exchange("NFO").instrumentType("CE").tickSize(.05).lotSize(50).build();
        when(master.findByInstrumentKey(instrument.getInstrumentKey())).thenReturn(Optional.of(instrument));
        doReturn(ok(new JSONObject().put("order_id","12345"))).when(service).exchange(eq("POST"),anyString(),anyMap(),any());
        doReturn(ok(new JSONArray())).when(service).exchange(eq("GET"),eq("/orders"),anyMap(),any());
    }
    static MStockBrokerService.Response ok(Object data) {
        return new MStockBrokerService.Response(200,new JSONObject().put("status","success").put("data",data));
    }
    JSONObject row(String status,long filled,long remaining) {
        return new JSONObject().put("order_id","12345").put("tradingsymbol",instrument.getTradingSymbol())
                .put("exchange",instrument.getExchange()).put("product",trade.getBrokerExecutionProduct()==null?"MIS":trade.getBrokerExecutionProduct())
                .put("transaction_type","BUY").put("quantity",50).put("filled_quantity",filled).put("pending_quantity",remaining)
                .put("average_price",filled>0?100:0).put("price",100).put("status",status).put("order_type","LIMIT").put("validity","DAY");
    }
    void book(JSONObject row) throws Exception { doReturn(ok(new JSONArray().put(row))).when(service).exchange(eq("GET"),eq("/orders"),anyMap(),any()); }
    @Test void submitsNrmlWithScopedCredentialsAndDurableClaim() throws Exception {
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isSuccess()).isTrue(); assertThat(result.getStatus()).isEqualTo("Pending");
        assertThat(trade.getBrokerExecutionProduct()).isEqualTo("NRML");
        var form=ArgumentCaptor.forClass(Map.class); var session=ArgumentCaptor.forClass(MStockBrokerService.Session.class);
        var order=inOrder(submissions,service);
        order.verify(submissions).claim(anyString(),eq(7L),eq("ENTRY:REQUEST:99:0"),eq(1));
        order.verify(service).exchange(eq("POST"),eq("/orders/regular"),form.capture(),session.capture());
        assertThat(form.getValue()).containsEntry("product","NRML").containsEntry("exchange","NFO").containsEntry("transaction_type","BUY").containsEntry("order_type","LIMIT");
        assertThat(session.getValue().apiKey()).isEqualTo("real-key");
        verify(submissions).accepted(anyString(),eq("12345"));
        verify(tokens,never()).getFirstNonExpiredTokenInfo(any());
    }
    @Test void awrPremiumBreakoutUsesBrokerStopLimitAndRemainsPendingUntilConfirmed() throws Exception {
        trade.setSource("awr");
        var result=service.placeTriggerPriceEntryOrder(trade,context,100,100.10);
        assertThat(result.isSuccess()).isTrue();assertThat(result.getStatus()).isEqualTo("Pending");assertThat(result.getExecutedQuantity()).isNull();
        var form=ArgumentCaptor.forClass(Map.class);verify(service).exchange(eq("POST"),eq("/orders/regular"),form.capture(),any());
        assertThat(form.getValue()).containsEntry("order_type","SL").containsEntry("trigger_price","100.0").containsEntry("price","100.1");
    }
    @Test void noCrossAccountFallbackOrDisabledEntry() throws Exception {
        account.setAppUserId(9L);
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        account.setAppUserId(2L); account.setTradingEnabled(false);
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        verify(service,never()).exchange(anyString(),anyString(),anyMap(),any()); verifyNoInteractions(submissions);
    }
    @Test void refusesInvalidContractLotPriceAndManagedProductBeforeSending() throws Exception {
        trade.setQuantity(51L); assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        trade.setQuantity(50L); assertThat(service.placeOrder(trade,context,100.01).isSuccess()).isFalse();
        assertThat(service.placeTriggerPriceEntryOrder(trade,context,101,100).isSuccess()).isFalse();
        trade.setBrokerSecurityId("NFO:WRONG"); assertThat(service.placeOrder(trade,context,100).isSuccess()).isFalse();
        trade.setBrokerProductType("BIGTRADEPLUS"); assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isFalse();
        verify(service,never()).exchange(anyString(),anyString(),anyMap(),any());
    }
    @Test void lostResponseIsPendingAndNeverRetransmitted() throws Exception {
        doThrow(new java.io.IOException("private response")).when(service).exchange(eq("POST"),anyString(),anyMap(),any());
        var first=service.placeOrder(trade,context,100);
        assertThat(first.isSuccess()).isTrue(); assertThat(first.getOrderId()).startsWith("MSTOCK-TAG-MS");
        String tag=first.getOrderId().substring("MSTOCK-TAG-".length());
        when(submissions.latest(7L,"ENTRY:REQUEST:99:0")).thenReturn(Optional.of(new MStockSubmissionStore.Attempt(tag,1,null,null)));
        var second=service.placeOrder(trade,context,100); assertThat(second.getOrderId()).isEqualTo(first.getOrderId());
        verify(service,times(1)).exchange(eq("POST"),anyString(),anyMap(),any());
        book(row("Complete",50,0).put("tag",tag));
        var status=service.fetchOrderStatus(trade,context,first.getOrderId());
        assertThat(status.getJSONArray("data").getJSONObject(0).getString("orderStatus")).isEqualTo("Fully Executed");
    }
    @Test void explicitBrokerRefusalDoesNotRefreshOrRetry() throws Exception {
        doReturn(new MStockBrokerService.Response(401,new JSONObject().put("status","error").put("error_type","TokenException")
            .put("message","secret-access"))).when(service).exchange(eq("POST"),anyString(),anyMap(),any());
        var result=service.placeOrder(trade,context,100);
        assertThat(result.isDefinitiveBrokerRejection()).isTrue(); assertThat(result.toString()).doesNotContain("secret-access");
        verify(submissions).inactive(anyString(),eq("REFUSED")); verify(tokens,never()).refreshToken(any(),any());
        verify(service,times(1)).exchange(eq("POST"),anyString(),anyMap(),any());
    }
    @Test void serverErrorOrMalformedSuccessIsAmbiguous() throws Exception {
        for (var response:List.of(new MStockBrokerService.Response(503,null),ok(new JSONObject()))) {
            doReturn(response).when(service).exchange(eq("POST"),anyString(),anyMap(),any());
            assertThat(service.placeOrder(trade,context,100).getOrderId()).startsWith("MSTOCK-TAG-");
        }
        verify(submissions,never()).inactive(anyString(),anyString());
    }
    @Test void modifyPreservesPartialQuantityAndStopTrigger() throws Exception {
        book(row("Pending",10,40).put("order_type","SL").put("trigger_price",99));
        doReturn(ok(new JSONObject().put("order_id","12345"))).when(service).exchange(eq("PUT"),anyString(),anyMap(),any());
        assertThat(service.modifyEntryOrder(trade,context,"12345",100).isSuccess()).isTrue();
        var form=ArgumentCaptor.forClass(Map.class);
        verify(service).exchange(eq("PUT"),eq("/orders/regular/12345"),form.capture(),any());
        assertThat(form.getValue()).containsEntry("quantity","50").containsEntry("order_type","SL").containsEntry("trigger_price","99");
    }
    @Test void cancelRequiresMatchingOrderAndAcknowledgement() throws Exception {
        book(row("Pending",10,40));
        doReturn(ok(new JSONObject())).when(service).exchange(eq("DELETE"),anyString(),anyMap(),any());
        service.cancelEntryOrder(trade,context,"12345");
        verify(service).exchange(eq("DELETE"),eq("/orders/regular/12345"),anyMap(),any());
        book(row("Pending",10,40).put("tradingsymbol","WRONG"));
        assertThatThrownBy(()->service.cancelEntryOrder(trade,context,"12345")).hasMessageContaining("unconfirmed");
    }
    @Test void fullAndPartialFillsRequireActualAverageAndMatchingIdentity() throws Exception {
        for (String status:List.of("Pending","Triggered","Complete","Cancelled","Rejected")) {
            book(row(status,0,50));
            assertThat(service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0).getString("orderStatus"))
                .isEqualTo(Set.of("Complete","Cancelled","Rejected").contains(status)?"Rejected":"Pending");
        }
        book(row("Complete",50,0).put("average_price",0)); assertThat(service.fetchOrderStatus(trade,context,"12345")).isNull();
        book(row("Complete",50,0).put("transaction_type","SELL")); assertThat(service.fetchOrderStatus(trade,context,"12345")).isNull();
    }
    @Test void partialCancelledExitStaysPendingAndDoesNotFreeJournal() throws Exception {
        trade.setExitOrderId("12345");
        book(row("Cancelled",10,0).put("transaction_type","SELL"));
        var status=service.fetchOrderStatus(trade,context,"12345").getJSONArray("data").getJSONObject(0);
        assertThat(status.getString("orderStatus")).isEqualTo("Pending reconciliation");
        verify(submissions,never()).inactive(anyString(),anyString());
    }
    @Test void exitRetainsProductWhenTradingDisabledAndRequiresNetPosition() throws Exception {
        account.setTradingEnabled(false);trade.setBrokerExecutionProduct("NRML");
        var net=new JSONArray().put(new JSONObject().put("tradingsymbol",instrument.getTradingSymbol()).put("exchange","NFO").put("product","NRML").put("quantity",50));
        doReturn(ok(new JSONObject().put("net",net))).when(service).exchange(eq("GET"),eq("/portfolio/positions"),anyMap(),any());
        assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isTrue();
        var form=ArgumentCaptor.forClass(Map.class); verify(service).exchange(eq("POST"),anyString(),form.capture(),any());
        assertThat(form.getValue()).containsEntry("product","NRML").containsEntry("transaction_type","SELL");
        net.getJSONObject(0).put("quantity",0);
        assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isFalse();
        verify(service,times(1)).exchange(eq("POST"),anyString(),anyMap(),any());
    }
    @Test void legacyExitKeepsMisAndExplicitZeroFillAllowsNewExitGeneration() throws Exception {
        when(submissions.latest(7L,"EXIT:88")).thenReturn(Optional.of(new MStockSubmissionStore.Attempt("MSprevious",1,"111","INACTIVE_ZERO_FILL")));
        var net=new JSONArray().put(new JSONObject().put("tradingsymbol",instrument.getTradingSymbol()).put("exchange","NFO").put("product","MIS").put("quantity",50));
        doReturn(ok(new JSONObject().put("net",net))).when(service).exchange(eq("GET"),eq("/portfolio/positions"),anyMap(),any());
        assertThat(service.placeExitOrder(trade,context,100).isSuccess()).isTrue();
        verify(submissions).claim(anyString(),eq(7L),eq("EXIT:88"),eq(2));
        var form=ArgumentCaptor.forClass(Map.class);verify(service).exchange(eq("POST"),anyString(),form.capture(),any());
        assertThat(form.getValue()).containsEntry("product","MIS");
    }
    @Test void afterHoursUsesAmoAndAmbiguousExitNeverAllowsNewPost() throws Exception {
        doReturn("amo").when(service).orderVariety();
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue();
        verify(service).exchange(eq("POST"),eq("/orders/amo"),anyMap(),any());
        when(submissions.latest(7L,"EXIT:88")).thenReturn(Optional.of(new MStockSubmissionStore.Attempt("MSunknownexit",1,null,null)));
        assertThat(service.placeExitOrder(trade,context,100).getOrderId()).isEqualTo("MSTOCK-TAG-MSunknownexit");
        verify(service,times(1)).exchange(eq("POST"),anyString(),anyMap(),any());
    }
    @Test void duplicateJournalClaimDoesNotPost() throws Exception {
        doThrow(new org.springframework.dao.DuplicateKeyException("duplicate")).when(submissions).claim(anyString(),anyLong(),anyString(),anyInt());
        assertThat(service.placeOrder(trade,context,100).getOrderId()).startsWith("MSTOCK-TAG-");
        verify(service,never()).exchange(eq("POST"),anyString(),anyMap(),any());
    }
    @Test void cashLongUsesCncAndShortCoverUsesMis() throws Exception {
        trade.setExchange("NC"); instrument.setExchange("NSE");instrument.setTradingSymbol("TEST-EQ");instrument.setInstrumentKey("NSE:TEST-EQ");instrument.setInstrumentType("EQ");
        when(instruments.resolveInstrumentKey(42)).thenReturn(Optional.of(instrument.getInstrumentKey()));
        when(master.findByInstrumentKey(instrument.getInstrumentKey())).thenReturn(Optional.of(instrument));
        assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue(); assertThat(trade.getBrokerExecutionProduct()).isEqualTo("CNC");
        trade.setEntryAction("SELL");assertThat(service.placeOrder(trade,context,100).isSuccess()).isTrue();assertThat(trade.getBrokerExecutionProduct()).isEqualTo("MIS");
    }
}
