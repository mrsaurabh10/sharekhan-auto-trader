package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dto.CloseTradesRequest;
import org.com.sharekhan.dto.CloseTradesResponse;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeCloseServiceTest {

    @Test
    void closeAllByContractCancelsPendingRequestsAndSquaresOffOpenTrades() {
        TriggerTradeRequestRepository requestRepository = mock(TriggerTradeRequestRepository.class);
        TriggeredTradeSetupRepository setupRepository = mock(TriggeredTradeSetupRepository.class);
        TradeExecutionService tradeExecutionService = mock(TradeExecutionService.class);
        LtpCacheService ltpCacheService = mock(LtpCacheService.class);
        WebSocketSubscriptionHelper subscriptionHelper = mock(WebSocketSubscriptionHelper.class);

        TradeCloseService service = new TradeCloseService(
                requestRepository,
                setupRepository,
                tradeExecutionService,
                ltpCacheService,
                subscriptionHelper
        );

        TriggerTradeRequestEntity request = TriggerTradeRequestEntity.builder()
                .id(11L)
                .symbol("NIFTY")
                .exchange("NF")
                .scripCode(12345)
                .appUserId(1L)
                .optionType("CE")
                .strikePrice(25000.0)
                .expiry("30/03/2026")
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .build();
        TriggeredTradeSetupEntity executed = TriggeredTradeSetupEntity.builder()
                .id(21L)
                .symbol("NIFTY")
                .scripCode(12345)
                .appUserId(1L)
                .entryPrice(100.0)
                .optionType("CE")
                .strikePrice(25000.0)
                .expiry("30-Mar-2026")
                .status(TriggeredTradeStatus.EXECUTED)
                .build();
        TriggeredTradeSetupEntity targetOrder = TriggeredTradeSetupEntity.builder()
                .id(22L)
                .symbol("NIFTY")
                .scripCode(12346)
                .appUserId(2L)
                .entryPrice(120.0)
                .optionType("CE")
                .strikePrice(25000.0)
                .expiry("2026-03-30")
                .status(TriggeredTradeStatus.TARGET_ORDER_PLACED)
                .build();
        TriggeredTradeSetupEntity differentStrike = TriggeredTradeSetupEntity.builder()
                .id(23L)
                .symbol("NIFTY")
                .scripCode(12347)
                .appUserId(3L)
                .entryPrice(130.0)
                .optionType("CE")
                .strikePrice(25100.0)
                .expiry("30/03/2026")
                .status(TriggeredTradeStatus.EXECUTED)
                .build();

        when(requestRepository.findBySymbolIgnoreCaseAndStatusIn(eq("NIFTY"), anyList()))
                .thenReturn(List.of(request));
        when(setupRepository.findBySymbolIgnoreCaseAndStatusIn(eq("NIFTY"), anyList()))
                .thenReturn(List.of(executed, targetOrder, differentStrike));
        when(ltpCacheService.getLtp(12345)).thenReturn(101.5);
        when(ltpCacheService.getLtp(12346)).thenReturn(null);

        CloseTradesRequest closeRequest = new CloseTradesRequest();
        closeRequest.setInstrument(" nifty ");
        closeRequest.setOptionType("CE");
        closeRequest.setStrikePrice(25000.0);
        closeRequest.setExpiry("30 March 2026");

        CloseTradesResponse response = service.closeAllByContract(closeRequest);

        assertEquals("NIFTY", response.getInstrument());
        assertEquals("CE", response.getOptionType());
        assertEquals(25000.0, response.getStrikePrice());
        assertEquals("2026-03-30", response.getExpiry());
        assertEquals(1, response.getCancelledRequests());
        assertEquals(2, response.getSquareOffInitiated());
        assertEquals(0, response.getErrors());
        assertEquals(0, response.getSkipped());

        verify(requestRepository).delete(request);
        verify(subscriptionHelper).unsubscribeFromScrip("NF12345");
        verify(tradeExecutionService).squareOff(executed, 101.5, "Manual contract close: NIFTY CE 25000.0 2026-03-30", TriggeredTradeStatus.EXIT_ORDER_PLACED);
        verify(tradeExecutionService).squareOff(targetOrder, 120.0, "Manual contract close: NIFTY CE 25000.0 2026-03-30", TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }
}
