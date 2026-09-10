package org.com.sharekhan.service;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.broker.SharekhanBrokerService;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BigTradePlusReconciliationServiceTest {

    @Test void shortProfitUsesBuybackAndMovesStopDownToCost() {
        var first=trade(1); var second=trade(2);
        first.setSource("spot-atr-pdl-bigtradeplus"); second.setSource("spot-atr-pdl-bigtradeplus");
        second.setStopLoss(86d);
        var p2=parent(2,83.50).put("buySell","SMP");
        var c2=child(2,false).put("triggerPrice",86d).put("buySell","BMP");
        service.reconcileReport(context,List.of(first,second),report(parent(1,84d).put("buySell","SMP"),
                child(1,true).put("execPrice",84d).put("buySell","BMP"),p2,c2));
        assertThat(first.getPnl()).isEqualTo(2.46);
        assertThat(first.getStatus()).isEqualTo(TriggeredTradeStatus.EXITED_SUCCESS);
        verify(broker).modifyBracketStop(context,p2,c2,84.80);
        assertThat(second.getStopLoss()).isEqualTo(86d);
    }
    final TriggeredTradeSetupRepository repo = mock(TriggeredTradeSetupRepository.class);
    final SharekhanBrokerService broker = mock(SharekhanBrokerService.class);
    final BigTradePlusReconciliationService service = new BigTradePlusReconciliationService(repo,
            mock(BrokerCredentialsRepository.class), mock(CryptoService.class), broker);
    final BrokerContext context = new BrokerContext(73196L, "key", "client", "Sharekhan", 2L);

    TriggeredTradeSetupEntity trade(long id) {
        return TriggeredTradeSetupEntity.builder().id(id).orderId("P"+id).quantity(3L).scripCode(15332)
                .brokerCredentialsId(2L).appUserId(1L).source("spot-atr-pdh-bigtradeplus")
                .triggeredAt(LocalDateTime.of(2026,9,10,9,20)).status(TriggeredTradeStatus.EXECUTED)
                .actualEntryPrice(84.85).stopLoss(84.50).build();
    }
    JSONObject parent(long id, double target) {
        return new JSONObject().put("orderId","P"+id).put("customerId",73196).put("scripCode",15332)
                .put("exchange","NC").put("orderType","BKT").put("orderQty",3)
                .put("execPrice",84.82).put("bookProfitPrice",target);
    }
    JSONObject child(long id, boolean executed) {
        return parent(id,85.55).put("orderId","C"+id).put("mpCoverOrderId","P"+id).put("childOrder",true)
                .put("orderStatus",executed?"FullyExecuted":"Pending").put("execQty",executed?3:0)
                .put("execPrice",executed?85.24:0).put("childTriggered","Book Profit Triggered")
                .put("triggerPrice",84.50).put("trailingStatus",executed?"TRACK_COMPLETED":"TRACK_INPROCESS")
                .put("lastModTime","2026-09-10 10:30:40");
    }
    JSONObject report(JSONObject... rows) { return new JSONObject().put("status",200).put("data",new JSONArray(List.of(rows))); }

    @Test void recordsActualFillAndRetriesStopUntilBrokerConfirms() {
        var first=trade(1); var second=trade(2); var p2=parent(2,85.55); var c2=child(2,false);
        var book=report(parent(1,85.25),child(1,true),p2,c2);
        service.reconcileReport(context,List.of(first,second),book);
        assertThat(first.getStatus()).isEqualTo(TriggeredTradeStatus.EXITED_SUCCESS);
        assertThat(first.getExitOrderId()).isEqualTo("C1");
        assertThat(first.getPnl()).isEqualTo(1.26);
        assertThat(second.getStopLoss()).isEqualTo(84.50);
        verify(broker).modifyBracketStop(context,p2,c2,84.85);
        service.reconcileReport(context,List.of(first,second),book);
        verify(repo,times(1)).save(first);
        verify(broker,times(2)).modifyBracketStop(context,p2,c2,84.85);
        c2.put("triggerPrice",84.85);
        service.reconcileReport(context,List.of(first,second),book);
        assertThat(second.getStopLoss()).isEqualTo(84.85);
        verify(broker,times(2)).modifyBracketStop(context,p2,c2,84.85);
    }
    @Test void rejectsPartialAndWrongAccountReports() {
        var first=trade(1);
        service.reconcileReport(context,List.of(first),report(parent(1,85.25),child(1,true).put("execQty",1)));
        service.reconcileReport(context,List.of(first),report(parent(1,85.25),child(1,true).put("customerId",999)));
        assertThat(first.getStatus()).isEqualTo(TriggeredTradeStatus.EXECUTED);
        verifyNoInteractions(repo,broker);
    }
    @Test void stopExitDoesNotAdvanceSibling() {
        var first=trade(1); var second=trade(2);
        service.reconcileReport(context,List.of(first,second),report(parent(1,85.25),
                child(1,true).put("childTriggered","Stop Loss Triggered"),parent(2,85.55),child(2,false)));
        assertThat(first.getExitReason()).isEqualTo("BROKER_BRACKET_EXIT");
        verifyNoInteractions(broker);
    }
}
