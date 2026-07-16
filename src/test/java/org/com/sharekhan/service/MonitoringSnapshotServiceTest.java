package org.com.sharekhan.service;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dto.monitoring.MonitoringSnapshotResponse;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitoringSnapshotServiceTest {

    @Test
    void returnsActiveTradeWithInstrumentAndSpotPrices() {
        TriggeredTradeSetupRepository repository = mock(TriggeredTradeSetupRepository.class);
        LtpCacheService cache = mock(LtpCacheService.class);
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(42L)
                .symbol("NIFTY")
                .scripCode(101)
                .spotScripCode(202)
                .status(TriggeredTradeStatus.EXECUTED)
                .entryPrice(100.0)
                .stopLoss(90.0)
                .target1(120.0)
                .build();
        when(repository.findByStatusIn(any())).thenReturn(List.of(trade));
        when(repository.findByStatusAndExitedAtBetweenOrderByExitedAtAsc(
                eq(TriggeredTradeStatus.EXITED_SUCCESS), any(), any())).thenReturn(List.of());
        when(cache.getLtp(101)).thenReturn(110.0);
        when(cache.getLtp(202)).thenReturn(22500.0);
        when(cache.getObservedAt(101)).thenReturn(LocalDateTime.of(2026, 7, 7, 10, 0));

        MonitoringSnapshotResponse response = new MonitoringSnapshotService(repository, cache).snapshot();

        assertEquals(1, response.activeTrades().size());
        assertEquals(42L, response.activeTrades().get(0).id());
        assertEquals(110.0, response.activeTrades().get(0).instrumentLtp());
        assertEquals(22500.0, response.activeTrades().get(0).spotLtp());
        assertNotNull(response.generatedAt());
    }
}
