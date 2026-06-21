package org.com.sharekhan.service;

import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
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
    void runsAtrSignalTradesForDateAndPersistsSuccessAndErrorResults() {
        TriggeredTradeSetupRepository tradeRepository = mock(TriggeredTradeSetupRepository.class);
        BacktestReplayService replayService = mock(BacktestReplayService.class);
        BacktestReplayResultRepository resultRepository = mock(BacktestReplayResultRepository.class);
        BrokerCredentialsRepository brokerCredentialsRepository = mock(BrokerCredentialsRepository.class);
        BacktestDailyReplayService service = new BacktestDailyReplayService(
                tradeRepository,
                replayService,
                resultRepository,
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
                eq(100L), any(), eq("CLOSE"), eq("15:20")))
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
        assertThat(response.getSuccessCount()).isEqualTo(1);
        assertThat(response.getErrorCount()).isEqualTo(1);
        assertThat(response.getFailedTradeSetupIds()).containsExactly(100L);
        ArgumentCaptor<BacktestReplayResultEntity> captor = ArgumentCaptor.forClass(BacktestReplayResultEntity.class);
        org.mockito.Mockito.verify(resultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(BacktestReplayResultEntity::getStatus)
                .containsExactly("SUCCESS", "ERROR");
        assertThat(captor.getAllValues().get(0).getBacktestPnl()).isEqualTo(250.0);
        assertThat(captor.getAllValues().get(1).getMessage()).contains("No candle");
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
