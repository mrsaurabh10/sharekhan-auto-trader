package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BacktestReplayResultRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestDailyReplayService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String ATR_SIGNAL_SOURCE = "atr-signal";
    private static final String TRIGGER_PRICE_POLICY = "CLOSE";
    private static final String SQUARE_OFF_TIME = "15:20";
    private static final List<String> INTERVALS = List.of("1minute", "5minute");

    private final TriggeredTradeSetupRepository tradeRepository;
    private final BacktestReplayService backtestReplayService;
    private final BacktestReplayResultRepository resultRepository;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void runTodayAfterMarketClose() {
        try {
            BacktestDailyReplayRunResponse response = runForDate(LocalDate.now(MARKET_ZONE));
            log.info("ATR backtest daily replay completed for {}: trades={}, results={}, success={}, errors={}",
                    response.getTradeDate(),
                    response.getTradeCount(),
                    response.getResultCount(),
                    response.getSuccessCount(),
                    response.getErrorCount());
        } catch (Exception ex) {
            log.error("ATR backtest daily replay failed.", ex);
        }
    }

    public BacktestDailyReplayRunResponse runForDate(LocalDate tradeDate) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("ATR backtest daily replay is already running.");
        }
        try {
            return runForDateInternal(tradeDate);
        } finally {
            running.set(false);
        }
    }

    private BacktestDailyReplayRunResponse runForDateInternal(LocalDate tradeDate) {
        LocalDate resolvedDate = tradeDate != null ? tradeDate : LocalDate.now(MARKET_ZONE);
        LocalDateTime start = resolvedDate.atStartOfDay();
        LocalDateTime end = resolvedDate.atTime(LocalTime.MAX);
        LocalDateTime runAt = LocalDateTime.now(MARKET_ZONE);
        List<TriggeredTradeSetupEntity> trades = tradeRepository.findBySourceForBacktestDate(
                ATR_SIGNAL_SOURCE,
                start,
                end);

        int successCount = 0;
        int errorCount = 0;
        List<Long> failedTradeSetupIds = new ArrayList<>();
        for (TriggeredTradeSetupEntity trade : trades) {
            for (String interval : INTERVALS) {
                BacktestReplayRequest request = request(interval);
                try {
                    BacktestReplayResponse response = backtestReplayService.replayTrade(trade.getId(), request);
                    saveSuccess(trade, response, interval, runAt);
                    successCount++;
                } catch (IllegalArgumentException ex) {
                    saveError(trade, interval, ex.getMessage(), runAt);
                    errorCount++;
                    failedTradeSetupIds.add(trade.getId());
                } catch (Exception ex) {
                    saveError(trade, interval, ex.getMessage(), runAt);
                    errorCount++;
                    failedTradeSetupIds.add(trade.getId());
                    log.warn("Backtest replay failed for trade {} interval {}.", trade.getId(), interval, ex);
                }
            }
        }

        return BacktestDailyReplayRunResponse.builder()
                .status("success")
                .tradeDate(resolvedDate)
                .source(ATR_SIGNAL_SOURCE)
                .tradeCount(trades.size())
                .resultCount(successCount + errorCount)
                .successCount(successCount)
                .errorCount(errorCount)
                .failedTradeSetupIds(failedTradeSetupIds.stream().distinct().toList())
                .runAt(runAt)
                .build();
    }

    private BacktestReplayRequest request(String interval) {
        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setIntradayOnly(true);
        request.setInterval(interval);
        request.setTriggerPricePolicy(TRIGGER_PRICE_POLICY);
        request.setSquareOffTime(SQUARE_OFF_TIME);
        return request;
    }

    private void saveSuccess(TriggeredTradeSetupEntity trade,
                             BacktestReplayResponse response,
                             String interval,
                             LocalDateTime runAt) {
        BacktestReplayResultEntity result = existingResult(trade.getId(), interval);
        populateTradeSnapshot(result, trade, runAt);
        result.setStatus("SUCCESS");
        result.setMessage(response.getMessage());
        BacktestReplayResponse.ResolvedConfig resolved = response.getResolved();
        if (resolved != null) {
            result.setTriggerPricePolicy(valueOrDefault(resolved.getTriggerPricePolicy(), TRIGGER_PRICE_POLICY));
            result.setSquareOffTime(valueOrDefault(resolved.getSquareOffTime(), SQUARE_OFF_TIME));
            result.setIntradayOnly(resolved.getIntradayOnly());
        }
        BacktestReplayResponse.Result actual = response.getActual();
        if (actual != null) {
            result.setActualEntryAt(actual.getEntryAt());
            result.setActualExitAt(actual.getExitAt());
            result.setActualExitPrice(actual.getExitPrice());
            result.setActualExitReason(actual.getExitReason());
            result.setActualPnl(actual.getPnl());
        }
        BacktestReplayResponse.Result backtest = response.getBacktest();
        if (backtest != null) {
            result.setBacktestEntryAt(backtest.getEntryAt());
            result.setBacktestExitAt(backtest.getExitAt());
            result.setBacktestExitPrice(backtest.getExitPrice());
            result.setBacktestExitReason(backtest.getExitReason());
            result.setBacktestPnl(backtest.getPnl());
            result.setBacktestExitCount(backtest.getExitCount());
        }
        resultRepository.save(result);
    }

    private void saveError(TriggeredTradeSetupEntity trade, String interval, String message, LocalDateTime runAt) {
        BacktestReplayResultEntity result = existingResult(trade.getId(), interval);
        populateTradeSnapshot(result, trade, runAt);
        result.setStatus("ERROR");
        result.setMessage(message);
        result.setBacktestEntryAt(null);
        result.setBacktestExitAt(null);
        result.setBacktestExitPrice(null);
        result.setBacktestExitReason(null);
        result.setBacktestPnl(null);
        result.setBacktestExitCount(null);
        resultRepository.save(result);
    }

    private BacktestReplayResultEntity existingResult(Long tradeSetupId, String interval) {
        return resultRepository
                .findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
                        tradeSetupId,
                        interval,
                        TRIGGER_PRICE_POLICY,
                        SQUARE_OFF_TIME)
                .orElseGet(() -> BacktestReplayResultEntity.builder()
                        .tradeSetupId(tradeSetupId)
                        .interval(interval)
                        .triggerPricePolicy(TRIGGER_PRICE_POLICY)
                        .squareOffTime(SQUARE_OFF_TIME)
                        .createdAt(LocalDateTime.now(MARKET_ZONE))
                        .build());
    }

    private void populateTradeSnapshot(BacktestReplayResultEntity result,
                                       TriggeredTradeSetupEntity trade,
                                       LocalDateTime runAt) {
        result.setTradeSetupId(trade.getId());
        result.setAppUserId(trade.getAppUserId());
        result.setBrokerCredentialsId(trade.getBrokerCredentialsId());
        result.setTradeDate(tradeDate(trade));
        result.setSource(trade.getSource());
        result.setSymbol(trade.getSymbol());
        result.setScripCode(trade.getScripCode());
        result.setOptionType(trade.getOptionType());
        result.setStrikePrice(trade.getStrikePrice());
        result.setExpiry(trade.getExpiry());
        result.setInterval(valueOrDefault(result.getInterval(), "1minute"));
        result.setTriggerPricePolicy(TRIGGER_PRICE_POLICY);
        result.setSquareOffTime(SQUARE_OFF_TIME);
        result.setIntradayOnly(true);
        result.setActualEntryAt(trade.getEntryAt());
        result.setActualExitAt(trade.getExitedAt());
        result.setActualExitPrice(trade.getExitPrice());
        result.setActualExitReason(trade.getExitReason());
        result.setActualPnl(trade.getPnl());
        populateBrokerSnapshot(result, trade.getBrokerCredentialsId());
        result.setRunAt(runAt);
        if (result.getCreatedAt() == null) {
            result.setCreatedAt(runAt);
        }
        result.setUpdatedAt(runAt);
    }

    private void populateBrokerSnapshot(BacktestReplayResultEntity result, Long brokerCredentialsId) {
        Optional<BrokerCredentialsEntity> broker = brokerCredentialsId == null
                ? Optional.empty()
                : brokerCredentialsRepository.findById(brokerCredentialsId);
        String brokerName = broker.map(BrokerCredentialsEntity::getBrokerName).orElse(null);
        result.setBrokerName(brokerName);
        result.setSimulator(brokerName != null && Broker.SIMULATOR.getDisplayName().equalsIgnoreCase(brokerName.trim()));
    }

    private LocalDate tradeDate(TriggeredTradeSetupEntity trade) {
        LocalDateTime at = trade.getEntryAt() != null ? trade.getEntryAt() : trade.getTriggeredAt();
        return at != null ? at.toLocalDate() : LocalDate.now(MARKET_ZONE);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
