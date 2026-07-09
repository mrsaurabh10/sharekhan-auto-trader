package org.com.sharekhan.service;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeRequestCleanupServiceTest {

    @Test
    void cancelPendingRequestsBySourceDeletesRequestsAndUnsubscribesTradeAndSpotFeeds() {
        TriggerTradeRequestRepository requestRepository = mock(TriggerTradeRequestRepository.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        WebSocketSubscriptionService subscriptionService = mock(WebSocketSubscriptionService.class);
        TradeRequestCleanupService service = new TradeRequestCleanupService(
                requestRepository,
                scriptMasterRepository,
                subscriptionService
        );

        TriggerTradeRequestEntity stockOptionRequest = TriggerTradeRequestEntity.builder()
                .id(11L)
                .source("atr-signal")
                .exchange("NF")
                .scripCode(76206)
                .spotScripCode(2144)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .build();
        TriggerTradeRequestEntity indexOptionRequest = TriggerTradeRequestEntity.builder()
                .id(12L)
                .source("atr-signal")
                .exchange("NF")
                .scripCode(51386)
                .spotScripCode(20000)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .build();
        ScriptMasterEntity bdlSpot = ScriptMasterEntity.builder()
                .scripCode(2144)
                .exchange("NC")
                .tradingSymbol("BDL")
                .build();
        ScriptMasterEntity niftySpot = ScriptMasterEntity.builder()
                .scripCode(20000)
                .exchange("NC")
                .tradingSymbol("NIFTY")
                .build();

        when(requestRepository.findBySourceIgnoreCaseAndStatusIn(eq("atr-signal"), anyList()))
                .thenReturn(List.of(stockOptionRequest, indexOptionRequest));
        when(scriptMasterRepository.findByScripCode(2144)).thenReturn(bdlSpot);
        when(scriptMasterRepository.findByScripCode(20000)).thenReturn(niftySpot);

        TradeRequestCleanupService.CleanupResult result = service.cancelPendingRequestsBySource("atr-signal");

        assertEquals("atr-signal", result.source());
        assertEquals(2, result.cancelled());
        assertEquals(0, result.errors());
        assertEquals(0, result.unsubscribeErrors());

        verify(requestRepository).delete(stockOptionRequest);
        verify(requestRepository).delete(indexOptionRequest);
        verify(subscriptionService).unsubscribeFromScrip("NF76206");
        verify(subscriptionService).unsubscribeFromScrip("NC2144");
        verify(subscriptionService).unsubscribeFromScrip("NF51386");
        verify(subscriptionService).unsubscribeFromScripLtp("NC20000");
    }
}
