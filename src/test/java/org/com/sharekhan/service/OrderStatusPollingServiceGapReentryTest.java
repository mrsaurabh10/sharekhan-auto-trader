package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.broker.BrokerServiceFactory;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStatusPollingServiceGapReentryTest {

    private final TriggerTradeRequestRepository requestRepo = mock(TriggerTradeRequestRepository.class);
    private final OrderStatusPollingService service = new OrderStatusPollingService(
            mock(TriggeredTradeSetupRepository.class),
            requestRepo,
            mock(WebSocketClientService.class),
            mock(TradeExecutionService.class),
            mock(WebSocketSubscriptionHelper.class),
            mock(BrokerCredentialsRepository.class),
            mock(BrokerServiceFactory.class));

    @Test
    void rearmsOriginalRequestOnlyAfterFirstGapFillExit() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .triggerRequestId(81L)
                .exitReason("GAP_FILL_STOP")
                .gapReentryCount(0)
                .build();
        when(requestRepo.rearmGapFillOnce(81L)).thenReturn(1);

        service.rearmGapFillRequest(trade);

        verify(requestRepo).rearmGapFillOnce(81L);
    }

    @Test
    void repeatedExitCallbackCannotGrantAnotherGapReentry() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .triggerRequestId(83L)
                .exitReason("GAP_FILL_STOP")
                .gapReentryCount(0)
                .build();
        when(requestRepo.rearmGapFillOnce(83L)).thenReturn(1, 0);

        service.rearmGapFillRequest(trade);
        service.rearmGapFillRequest(trade);

        verify(requestRepo, times(2)).rearmGapFillOnce(83L);
    }

    @Test
    void doesNotRearmAfterOneGapRetriggerWasAlreadyUsed() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .triggerRequestId(82L)
                .exitReason("GAP_FILL_STOP")
                .gapReentryCount(1)
                .build();

        service.rearmGapFillRequest(trade);

        verify(requestRepo, never()).rearmGapFillOnce(82L);
    }
}
