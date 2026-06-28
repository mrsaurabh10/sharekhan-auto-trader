package org.com.sharekhan.service;

import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRangeRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.BacktestReplayEventRepository;
import org.com.sharekhan.repository.BacktestReplayResultRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestDailyReplayServiceTest {

    @Test
    void previousAvailableTradeDateSkipsWeekend() {
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                mock(TriggeredTradeSetupRepository.class),
                mock(BacktestReplayService.class),
                mock(BacktestReplayResultRepository.class),
                mock(BacktestReplayEventRepository.class),
                mock(BrokerCredentialsRepository.class));

        assertThat(service.previousAvailableTradeDate(LocalDate.of(2026, 6, 22)))
                .isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(service.previousAvailableTradeDate(LocalDate.of(2026, 6, 23)))
                .isEqualTo(LocalDate.of(2026, 6, 22));
    }

    @Test
    void rangeReplayRunsWeekdaysSequentially() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                tradeRepository,
                mock(BacktestReplayService.class),
                mock(BacktestReplayResultRepository.class),
                mock(BacktestReplayEventRepository.class),
                mock(BrokerCredentialsRepository.class));
        when(tradeRepository.findBySourceForBacktestDate(eq("atr-signal"), any(), any()))
                .thenReturn(List.of());

        BacktestDailyReplayRangeRunResponse response = service.runForDateRange(
                LocalDate.of(2026, 6, 12),
                LocalDate.of(2026, 6, 15));

        assertThat(response.getDayCount()).isEqualTo(2);
        assertThat(response.getTradeCount()).isZero();
        org.mockito.Mockito.verify(tradeRepository).findBySourceForBacktestDate(
                eq("atr-signal"),
                eq(LocalDateTime.of(2026, 6, 12, 0, 0)),
                eq(LocalDate.of(2026, 6, 12).atTime(java.time.LocalTime.MAX)));
        org.mockito.Mockito.verify(tradeRepository).findBySourceForBacktestDate(
                eq("atr-signal"),
                eq(LocalDateTime.of(2026, 6, 15, 0, 0)),
                eq(LocalDate.of(2026, 6, 15).atTime(java.time.LocalTime.MAX)));
        org.mockito.Mockito.verify(tradeRepository, org.mockito.Mockito.times(2))
                .findBySourceForBacktestDate(eq("atr-signal"), any(), any());
    }

    @Test
    void runsAtrSignalTradesForDateAndPersistsSuccessAndErrorResults() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        BacktestReplayService replayService = mock(BacktestReplayService.class);
        BacktestReplayResultRepository resultRepository = mock(BacktestReplayResultRepository.class);
        BacktestReplayEventRepository eventRepository = mock(BacktestReplayEventRepository.class);
        BrokerCredentialsRepository brokerCredentialsRepository = mock(BrokerCredentialsRepository.class);
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                tradeRepository,
                replayService,
                resultRepository,
                eventRepository,
                brokerCredentialsRepository);
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(100L)
                .appUserId(3L)
                .source("atr-signal")
                .symbol("ITC")
                .scripCode(107866)
                .entryAt(LocalDateTime.of(2026, 6, 19, 15, 8))
                .pnl(-100.0)
                .exitReason("STOP_LOSS_HIT")
                .build();
        when(tradeRepository.findBySourceForBacktestDate(
                eq("atr-signal"),
                eq(LocalDateTime.of(2026, 6, 19, 0, 0)),
                eq(LocalDate.of(2026, 6, 19).atTime(java.time.LocalTime.MAX))))
                .thenReturn(List.of(trade));
        when(resultRepository.findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
                eq(100L), any(), any(), eq("15:20")))
                .thenReturn(Optional.empty());
        when(replayService.replayTrade(eq(100L), any()))
                .thenAnswer(invocation -> {
                    String interval = invocation.getArgument(1, org.com.sharekhan.dto.backtest.BacktestReplayRequest.class).getInterval();
                    if ("5minute".equals(interval)) {
                        throw new IllegalArgumentException("No candle available to square off remaining quantity.");
                    }
                    return replayResponse();
                });

        BacktestDailyReplayRunResponse response = service.runForDate(LocalDate.of(2026, 6, 19));

        assertThat(response.getTradeCount()).isEqualTo(1);
        assertThat(response.getSuccessCount()).isEqualTo(2);
        assertThat(response.getErrorCount()).isEqualTo(1);
        assertThat(response.getFailedTradeSetupIds()).containsExactly(100L);
        ArgumentCaptor<BacktestReplayResultEntity> captor = ArgumentCaptor.forClass(BacktestReplayResultEntity.class);
        org.mockito.Mockito.verify(resultRepository, org.mockito.Mockito.times(3)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BacktestReplayResultEntity::getStatus)
                .containsExactly("SUCCESS", "ERROR", "SUCCESS");
        assertThat(captor.getAllValues().get(0).getBacktestPnl()).isEqualTo(250.0);
        assertThat(captor.getAllValues().get(1).getMessage()).contains("No candle");
        assertThat(captor.getAllValues().get(2).getTriggerPricePolicy()).isEqualTo("CLOSE_REENTRY");
    }

    @Test
    void skipsExistingSuccessfulScenarioInsteadOfOverwritingIt() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        BacktestReplayService replayService = mock(BacktestReplayService.class);
        BacktestReplayResultRepository resultRepository = mock(BacktestReplayResultRepository.class);
        BacktestReplayEventRepository eventRepository = mock(BacktestReplayEventRepository.class);
        BrokerCredentialsRepository brokerCredentialsRepository = mock(BrokerCredentialsRepository.class);
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                tradeRepository,
                replayService,
                resultRepository,
                eventRepository,
                brokerCredentialsRepository);
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(101L)
                .appUserId(3L)
                .source("atr-signal")
                .symbol("ITC")
                .scripCode(107866)
                .entryAt(LocalDateTime.of(2026, 6, 19, 15, 8))
                .build();
        when(tradeRepository.findBySourceForBacktestDate(
                eq("atr-signal"),
                eq(LocalDateTime.of(2026, 6, 19, 0, 0)),
                eq(LocalDate.of(2026, 6, 19).atTime(java.time.LocalTime.MAX))))
                .thenReturn(List.of(trade));
        BacktestReplayResultEntity existingSuccess = BacktestReplayResultEntity.builder()
                .tradeSetupId(101L)
                .interval("1minute")
                .triggerPricePolicy("CLOSE")
                .squareOffTime("15:20")
                .status("SUCCESS")
                .build();
        when(resultRepository.findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
                eq(101L), any(), any(), eq("15:20")))
                .thenAnswer(invocation -> {
                    String interval = invocation.getArgument(1, String.class);
                    String policy = invocation.getArgument(2, String.class);
                    if ("1minute".equals(interval) && "CLOSE".equals(policy)) {
                        return Optional.of(existingSuccess);
                    }
                    return Optional.empty();
                });
        when(replayService.replayTrade(eq(101L), any())).thenReturn(replayResponse());

        BacktestDailyReplayRunResponse response = service.runForDate(LocalDate.of(2026, 6, 19));

        assertThat(response.getResultCount()).isEqualTo(3);
        assertThat(response.getSkippedCount()).isEqualTo(1);
        assertThat(response.getSuccessCount()).isEqualTo(2);
        assertThat(response.getErrorCount()).isZero();
        org.mockito.Mockito.verify(replayService, org.mockito.Mockito.times(2)).replayTrade(eq(101L), any());
        ArgumentCaptor<BacktestReplayResultEntity> captor = ArgumentCaptor.forClass(BacktestReplayResultEntity.class);
        org.mockito.Mockito.verify(resultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(BacktestReplayResultEntity::getTriggerPricePolicy)
                .containsExactly("CLOSE", "CLOSE_REENTRY");
        assertThat(captor.getAllValues()).extracting(BacktestReplayResultEntity::getInterval)
                .containsExactly("5minute", "1minute");
    }

    @Test
    void skipsTslChildRowsAndReplaysOnlyRootTrade() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        BacktestReplayService replayService = mock(BacktestReplayService.class);
        BacktestReplayResultRepository resultRepository = mock(BacktestReplayResultRepository.class);
        BacktestReplayEventRepository eventRepository = mock(BacktestReplayEventRepository.class);
        BrokerCredentialsRepository brokerCredentialsRepository = mock(BrokerCredentialsRepository.class);
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                tradeRepository,
                replayService,
                resultRepository,
                eventRepository,
                brokerCredentialsRepository);
        LocalDateTime entryAt = LocalDateTime.of(2026, 6, 19, 9, 20);
        TriggeredTradeSetupEntity root = TriggeredTradeSetupEntity.builder()
                .id(100L)
                .source("atr-signal")
                .symbol("ITC")
                .scripCode(107866)
                .spotScripCode(1660)
                .exchange("NF")
                .optionType("CE")
                .strikePrice(420.0)
                .expiry("30/06/2026")
                .entryAt(entryAt)
                .triggeredAt(entryAt)
                .tslEnabled(true)
                .quantity(2400L)
                .lots(3)
                .originalLots(3)
                .build();
        TriggeredTradeSetupEntity child = TriggeredTradeSetupEntity.builder()
                .id(101L)
                .source("atr-signal")
                .symbol("ITC")
                .scripCode(107866)
                .spotScripCode(1660)
                .exchange("NF")
                .optionType("CE")
                .strikePrice(420.0)
                .expiry("30/06/2026")
                .entryAt(entryAt)
                .triggeredAt(entryAt)
                .tslEnabled(true)
                .quantity(1600L)
                .lots(2)
                .originalLots(3)
                .build();
        when(tradeRepository.findBySourceForBacktestDate(
                eq("atr-signal"),
                eq(LocalDateTime.of(2026, 6, 19, 0, 0)),
                eq(LocalDate.of(2026, 6, 19).atTime(java.time.LocalTime.MAX))))
                .thenReturn(List.of(child, root));
        when(resultRepository.findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
                eq(100L), any(), any(), eq("15:20")))
                .thenReturn(Optional.empty());
        when(replayService.replayTrade(eq(100L), any())).thenReturn(replayResponse());

        BacktestDailyReplayRunResponse response = service.runForDate(LocalDate.of(2026, 6, 19));

        assertThat(response.getTradeCount()).isEqualTo(1);
        assertThat(response.getSuccessCount()).isEqualTo(3);
        org.mockito.Mockito.verify(replayService, org.mockito.Mockito.times(3)).replayTrade(eq(100L), any());
        org.mockito.Mockito.verify(replayService, org.mockito.Mockito.never()).replayTrade(eq(101L), any());
    }

    private BacktestReplayResponse replayResponse() {
        return BacktestReplayResponse.builder()
                .status("success")
                .message("Replay completed")
                .resolved(BacktestReplayResponse.ResolvedConfig.builder()
                        .interval("1minute")
                        .intradayOnly(true)
                        .triggerPricePolicy("CLOSE")
                        .squareOffTime("15:20")
                        .build())
                .actual(BacktestReplayResponse.Result.builder()
                        .entryAt(LocalDateTime.of(2026, 6, 19, 15, 8))
                        .exitAt(LocalDateTime.of(2026, 6, 19, 15, 9))
                        .exitReason("STOP_LOSS_HIT")
                        .pnl(-100.0)
                        .build())
                .backtest(BacktestReplayResponse.Result.builder()
                        .entryAt(LocalDateTime.of(2026, 6, 19, 15, 8))
                        .exitAt(LocalDateTime.of(2026, 6, 19, 15, 15))
                        .exitReason("TRAILING_SL_HIT")
                        .exitPrice(10.0)
                        .pnl(250.0)
                        .exitCount(1)
                        .build())
                .build();
    }
}
