package org.com.sharekhan.scheduler;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntradayTradeCloserTest {

    @Test
    void purgeRemovesStaleRequestsAndKeepsPendingConfirmationsAndPostMarketRequests() {
        TriggerTradeRequestRepository requestRepository = mock(TriggerTradeRequestRepository.class);
        when(requestRepository.deleteStaleRequestsCreatedBeforeExceptStatus(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);
        IntradayTradeCloser scheduler = new IntradayTradeCloser(
                mock(TriggeredTradeSetupRepository.class),
                requestRepository,
                mock(TradeExecutionService.class),
                mock(LtpCacheService.class));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        scheduler.purgeTodayIntradayTradeRequests();

        verify(requestRepository).deleteStaleRequestsCreatedBeforeExceptStatus(
                eq(today.atTime(15, 30)), eq(org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION));
    }
}
