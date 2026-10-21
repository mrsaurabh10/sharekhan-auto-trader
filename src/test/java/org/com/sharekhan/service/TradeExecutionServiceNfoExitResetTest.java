package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class TradeExecutionServiceNfoExitResetTest {

    private final TradeExecutionService service = new TradeExecutionService(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    @Test
    void unknownNfoExitIsKeptUntilBrokerConfirmsItsOutcome() {
        var trades=org.mockito.Mockito.mock(org.com.sharekhan.repository.TriggeredTradeSetupRepository.class);
        var credentials=org.mockito.Mockito.mock(org.com.sharekhan.repository.BrokerCredentialsRepository.class);
        var brokers=org.mockito.Mockito.mock(org.com.sharekhan.service.broker.BrokerServiceFactory.class);
        var broker=org.mockito.Mockito.mock(TestBroker.class);
        var trade=nfoOptionTargetOrder();trade.setId(12L);trade.setAppUserId(4L);trade.setBrokerCredentialsId(7L);trade.setExitOrderId("12345");
        org.mockito.Mockito.when(trades.findById(12L)).thenReturn(java.util.Optional.of(trade));
        org.mockito.Mockito.when(credentials.findById(7L)).thenReturn(java.util.Optional.of(org.com.sharekhan.entity.BrokerCredentialsEntity.builder()
            .id(7L).appUserId(4L).brokerName("Shoonya").active(true).build()));
        org.mockito.Mockito.when(brokers.getService("Shoonya")).thenReturn(broker);
        var engine=new TradeExecutionService(trades,null,null,null,null,null,null,null,null,null,null,null,credentials,brokers,new OrderPlacementGuard(),null);
        assertThat(engine.resetNonIntradayExitOrderIfInactive(12L)).isEqualTo(TradeExecutionService.EndOfDayExitResetResult.SKIPPED_BROKER_UNAVAILABLE);
        assertThat(trade.getExitOrderId()).isEqualTo("12345");
        org.mockito.Mockito.verify(broker).fetchOrderStatus(org.mockito.ArgumentMatchers.eq(trade),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("12345"));
        org.mockito.Mockito.verify(trades,org.mockito.Mockito.never()).resetInactiveNonIntradayExitState(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
    }

    private interface TestBroker extends org.com.sharekhan.service.broker.BrokerService, org.com.sharekhan.service.broker.OrderStatusBrokerService {}

    @Test
    void normalizesApiCashEquityExchangeBeforeScriptMasterLookup() {
        TriggerRequest request = new TriggerRequest();
        request.setInstrument("SATIN");
        request.setExchange("NSE");

        TradeExecutionService.normalizeCashEquityExchange(request);

        assertThat(request.getExchange()).isEqualTo("NC");
        assertThat(request.getInstrument()).isEqualTo("SATIN");
    }

    @Test
    void preservesOptionExchangeForOptionRequests() {
        TriggerRequest request = new TriggerRequest();
        request.setExchange("NSE");
        request.setOptionType("CE");

        TradeExecutionService.normalizeCashEquityExchange(request);

        assertThat(request.getExchange()).isEqualTo("NSE");
    }

    private TriggeredTradeSetupEntity nfoOptionTargetOrder() {
        return TriggeredTradeSetupEntity.builder()
                .exchange("NF")
                .optionType("CE")
                .intraday(false)
                .status(TriggeredTradeStatus.TARGET_ORDER_PLACED)
                .build();
    }
    @Test void failedExitResetRequiresExplicitZeroFillAndRetiresOnlyThatBrokerOrder() {
        var trades=org.mockito.Mockito.mock(org.com.sharekhan.repository.TriggeredTradeSetupRepository.class);
        var credentials=org.mockito.Mockito.mock(org.com.sharekhan.repository.BrokerCredentialsRepository.class);
        var brokers=org.mockito.Mockito.mock(org.com.sharekhan.service.broker.BrokerServiceFactory.class);
        var broker=org.mockito.Mockito.mock(TestBroker.class);
        var submissions=org.mockito.Mockito.mock(BrokerExitSubmissionStore.class);
        var trade=nfoOptionTargetOrder();trade.setId(12L);trade.setAppUserId(4L);trade.setBrokerCredentialsId(7L);
        trade.setStatus(TriggeredTradeStatus.EXIT_FAILED);trade.setExitOrderId("12345");trade.setQuantity(65L);
        org.mockito.Mockito.when(trades.findById(12L)).thenReturn(java.util.Optional.of(trade));
        org.mockito.Mockito.when(credentials.findById(7L)).thenReturn(java.util.Optional.of(org.com.sharekhan.entity.BrokerCredentialsEntity.builder()
            .id(7L).appUserId(4L).brokerName("Shoonya").build()));
        org.mockito.Mockito.when(brokers.getService("Shoonya")).thenReturn(broker);
        var row=new org.json.JSONObject().put("orderStatus","Cancelled").put("quantity",65).put("execQty",3).put("openQty",62);
        org.mockito.Mockito.when(broker.fetchOrderStatus(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new org.json.JSONObject().put("data",new org.json.JSONArray().put(row)));
        var engine=new TradeExecutionService(trades,null,null,null,null,null,null,null,null,null,null,null,credentials,brokers,new OrderPlacementGuard(),null);
        org.springframework.test.util.ReflectionTestUtils.setField(engine,"exitSubmissions",submissions);
        assertThat(engine.resetNonIntradayExitOrderIfInactive(12L)).isEqualTo(TradeExecutionService.EndOfDayExitResetResult.SKIPPED_BROKER_UNAVAILABLE);
        org.mockito.Mockito.verifyNoInteractions(submissions);
        row.put("execQty",0).put("openQty",65);
        org.mockito.Mockito.when(trades.resetInactiveNonIntradayExitState(org.mockito.ArgumentMatchers.eq(12L),org.mockito.ArgumentMatchers.any())).thenReturn(1);
        assertThat(engine.resetNonIntradayExitOrderIfInactive(12L)).isEqualTo(TradeExecutionService.EndOfDayExitResetResult.RESET);
        org.mockito.Mockito.verify(submissions).inactive(12L,7L,"12345");
    }

}
