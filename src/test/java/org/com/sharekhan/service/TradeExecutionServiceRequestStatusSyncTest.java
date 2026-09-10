package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.junit.jupiter.api.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeExecutionServiceRequestStatusSyncTest {

    private final TriggerTradeRequestRepository requestRepo = mock(TriggerTradeRequestRepository.class);
    private final TradeExecutionService service = new TradeExecutionService(
            null, requestRepo, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    @Test
    void synchronizesPendingRequestWhenEntryIsExecuted() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(7261L)
                .triggerRequestId(9924L)
                .build();
        when(requestRepo.claimIfStatusEquals(9924L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.EXECUTED.name())).thenReturn(1);

        service.syncLinkedEntryRequestStatus(trade, TriggeredTradeStatus.EXECUTED);

        verify(requestRepo).claimIfStatusEquals(9924L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.EXECUTED.name());
        verify(requestRepo, never()).claimIfStatusEquals(9924L,
                TriggeredTradeStatus.TRIGGERED.name(), TriggeredTradeStatus.EXECUTED.name());
    }

    @Test
    void synchronizesTriggeredRequestWhenEntryIsRejected() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(7262L)
                .triggerRequestId(9925L)
                .build();
        when(requestRepo.claimIfStatusEquals(9925L,
                TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.name(),
                TriggeredTradeStatus.REJECTED.name())).thenReturn(0);
        when(requestRepo.claimIfStatusEquals(9925L,
                TriggeredTradeStatus.TRIGGERED.name(),
                TriggeredTradeStatus.REJECTED.name())).thenReturn(1);

        service.syncLinkedEntryRequestStatus(trade, TriggeredTradeStatus.REJECTED);

        verify(requestRepo).claimIfStatusEquals(9925L,
                TriggeredTradeStatus.TRIGGERED.name(), TriggeredTradeStatus.REJECTED.name());
    }

    @Test
    void ignoresTradesWithoutLinkedRequests() {
        service.syncLinkedEntryRequestStatus(TriggeredTradeSetupEntity.builder().id(7263L).build(),
                TriggeredTradeStatus.EXECUTED);

        verify(requestRepo, never()).claimIfStatusEquals(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void recognizesSharekhanTriggeredRequestStatusWhileOrderRemainsPending() {
        JSONObject history = new JSONObject().put("data", new JSONArray().put(new JSONObject()
                .put("orderStatus", "Pending")
                .put("requestStatus", "TRIGGERED")
                .put("openQty", 700)
                .put("execQty", 0)));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "isBrokerTriggerActivated", history)).isTrue();
    }
}
