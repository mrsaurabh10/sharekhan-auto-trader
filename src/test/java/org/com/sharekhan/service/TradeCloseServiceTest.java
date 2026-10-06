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
import java.util.Arrays;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TradeCloseServiceTest {

    @Test
    void sourceScopedCloseOnlyCancelsAndClosesSharekhanContracts() {
        TriggerTradeRequestRepository requestRepository = mock(TriggerTradeRequestRepository.class);
        TriggeredTradeSetupRepository setupRepository = mock(TriggeredTradeSetupRepository.class);
        TradeExecutionService executionService = mock(TradeExecutionService.class);
        LtpCacheService ltpCache = mock(LtpCacheService.class);
        WebSocketSubscriptionHelper subscriptions = mock(WebSocketSubscriptionHelper.class);
        TradeCloseService service = new TradeCloseService(
                requestRepository, setupRepository, executionService, ltpCache, subscriptions);

        List<TriggerTradeRequestEntity> requests = new ArrayList<>();
        List<TriggeredTradeSetupEntity> trades = new ArrayList<>();
        List<String> sources = Arrays.asList(" shareKHAN ", "strategy:ST_RSI_EMA_ADX_CE",
                "strategy:ST_RSI_EMA_ADX_PE", "atr-signal", "StockBazaari", "telegram", null,
                "Sharekhan-other");
        for (int i = 0; i < sources.size(); i++) {
            requests.add(TriggerTradeRequestEntity.builder()
                    .id(100L + i).symbol("NIFTY").exchange("NF").scripCode(40701)
                    .source(sources.get(i)).optionType("CE").strikePrice(22600.0)
                    .expiry("06/10/2026").status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                    .build());
            trades.add(TriggeredTradeSetupEntity.builder()
                    .id(200L + i).symbol("NIFTY").scripCode(40701)
                    .source(sources.get(i)).optionType("CE").strikePrice(22600.0)
                    .expiry("06/10/2026").status(TriggeredTradeStatus.EXECUTED).build());
        }
        when(requestRepository.findBySymbolIgnoreCaseAndStatusIn(eq("NIFTY"), anyList()))
                .thenReturn(requests);
        when(setupRepository.findBySymbolIgnoreCaseAndStatusIn(eq("NIFTY"), anyList()))
                .thenReturn(trades);
        CloseTradesRequest close = new CloseTradesRequest();
        close.setInstrument("NIFTY");
        close.setOptionType("CE");
        close.setStrikePrice(22600.0);
        close.setExpiry("06/10/2026");
        close.setSource("Sharekhan");
        close.setReason("Sharekhan UPDATE notification");
        close.setPrice(91.9);

        CloseTradesResponse response = service.closeAllByContract(close);

        assertEquals(1, response.getCancelledRequests());
        assertEquals(1, response.getSquareOffInitiated());
        assertEquals(0, response.getErrors());
        verify(requestRepository).findBySymbolIgnoreCaseAndStatusIn(eq("NIFTY"), anyList());
        verify(requestRepository).delete(requests.get(0));
        verify(executionService).squareOff(trades.get(0), 91.9,
                "Sharekhan UPDATE notification", TriggeredTradeStatus.EXIT_ORDER_PLACED);
        verify(executionService).releaseOptionFeedIfUnused("NF", 40701);
        verifyNoMoreInteractions(requestRepository, executionService, subscriptions, ltpCache);
    }

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
        verify(tradeExecutionService).releaseOptionFeedIfUnused("NF", 12345);
        verify(tradeExecutionService).squareOff(executed, 101.5, "Manual contract close: NIFTY CE 25000.0 2026-03-30", TriggeredTradeStatus.EXIT_ORDER_PLACED);
        verify(tradeExecutionService).squareOff(targetOrder, 120.0, "Manual contract close: NIFTY CE 25000.0 2026-03-30", TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }
}
