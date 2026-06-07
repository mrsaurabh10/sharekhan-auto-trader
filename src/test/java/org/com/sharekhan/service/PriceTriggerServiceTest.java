package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceTriggerServiceTest {

    private final TriggerTradeRequestRepository triggerRepo = mock(TriggerTradeRequestRepository.class);
    private final TriggeredTradeSetupRepository triggeredRepo = mock(TriggeredTradeSetupRepository.class);
    private final TradeExecutionService tradeExecutionService = mock(TradeExecutionService.class);
    private final LtpCacheService ltpCacheService = mock(LtpCacheService.class);

    private final PriceTriggerService service = new PriceTriggerService(
            triggerRepo,
            triggeredRepo,
            tradeExecutionService,
            new NoopTransactionManager(),
            mock(ScriptMasterRepository.class),
            mock(WebSocketSubscriptionService.class),
            ltpCacheService,
            mock(MStockLtpService.class),
            mock(MStockInstrumentResolver.class),
            mock(SharekhanHistoricalService.class),
            mock(ScripExecutorManager.class)
    );

    @Test
    void monitorOpenTradesDoesNotUseSpotTickAsTradedPriceWhenOptionScripMatchesSpot() {
        TriggeredTradeSetupEntity trade = optionTrade(5209L, 20000, 20000);
        trade.setTarget1(131.2);
        trade.setExitOrderId("183759611");

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));

        service.monitorOpenTrades(20000, 23357.0);

        verify(tradeExecutionService, never()).hasUsableTradedExitPrice(any(), anyDouble());
        verify(tradeExecutionService, never()).modifyExitOrderForTarget(any(), anyDouble());
        verify(tradeExecutionService, never()).squareOff(any(), anyDouble(), anyString());
        verify(triggeredRepo, never()).claimIfStatusEquals(any(), anyString(), anyString(), anyString());
    }

    @Test
    void monitorOpenTradesUsesCachedOptionLtpWhenSpotTickTriggersSpotTarget() {
        TriggeredTradeSetupEntity trade = optionTrade(5210L, 999999, 20000);
        trade.setUseSpotForTarget(true);
        trade.setTarget1(23400.0);

        when(triggeredRepo.findByScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of());
        when(triggeredRepo.findBySpotScripCodeAndStatusIn(eq(20000), anyList())).thenReturn(List.of(trade));
        when(ltpCacheService.getLtp(999999)).thenReturn(120.0);
        when(triggeredRepo.findById(5210L)).thenReturn(Optional.of(trade));
        when(tradeExecutionService.hasUsableTradedExitPrice(trade, 120.0)).thenReturn(true);
        when(triggeredRepo.claimIfStatusEquals(eq(5210L), anyString(), anyString(), anyString())).thenReturn(0);

        service.monitorOpenTrades(20000, 23357.0);

        verify(tradeExecutionService).hasUsableTradedExitPrice(trade, 120.0);
        verify(tradeExecutionService, never()).hasUsableTradedExitPrice(trade, 23357.0);
        verify(tradeExecutionService, never()).modifyExitOrderForTarget(any(), eq(23357.0));
        verify(tradeExecutionService, never()).squareOff(any(), eq(23357.0), anyString());
    }

    private TriggeredTradeSetupEntity optionTrade(Long id, Integer scripCode, Integer spotScripCode) {
        return TriggeredTradeSetupEntity.builder()
                .id(id)
                .symbol("NIFTY")
                .scripCode(scripCode)
                .spotScripCode(spotScripCode)
                .optionType("PE")
                .entryPrice(110.0)
                .actualEntryPrice(111.2)
                .status(TriggeredTradeStatus.EXECUTED)
                .useSpotForEntry(false)
                .useSpotForSl(false)
                .useSpotForTarget(false)
                .build();
    }

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
