package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRunResponse;
import org.com.sharekhan.dto.backtest.BacktestDailyReplayRangeRunResponse;
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

import java.time.DayOfWeek;
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
    private static final String REENTRY_TRIGGER_PRICE_POLICY = "CLOSE_REENTRY";
    private static final String SQUARE_OFF_TIME = "15:20";
    private static final List<ReplayScenario> SCENARIOS = List.of(
            new ReplayScenario("1minute", TRIGGER_PRICE_POLICY, TRIGGER_PRICE_POLICY, false),
            new ReplayScenario("5minute", TRIGGER_PRICE_POLICY, TRIGGER_PRICE_POLICY, false),
            new ReplayScenario("1minute", TRIGGER_PRICE_POLICY, REENTRY_TRIGGER_PRICE_POLICY, true)
    );

    private final TriggeredTradeSetupRepository tradeRepository;
    private final BacktestReplayService backtestReplayService;
    private final BacktestReplayResultRepository resultRepository;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreviousAvailableDayAfterMarketClose() {
        try {
            LocalDate replayDate = previousAvailableTradeDate(LocalDate.now(MARKET_ZONE));
            BacktestDailyReplayRunResponse response = runForDate(replayDate);
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

    public BacktestDailyReplayRangeRunResponse runForDateRange(LocalDate from, LocalDate to) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("ATR backtest daily replay is already running.");
        }
        try {
            return runForDateRangeInternal(from, to);
        } finally {
            running.set(false);
        }
    }

    private BacktestDailyReplayRangeRunResponse runForDateRangeInternal(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : previousAvailableTradeDate(LocalDate.now(MARKET_ZONE));
        LocalDate resolvedFrom = from != null ? from : resolvedTo;
        if (resolvedFrom.isAfter(resolvedTo)) {
            LocalDate previousFrom = resolvedFrom;
            resolvedFrom = resolvedTo;
            resolvedTo = previousFrom;
        }

        List<BacktestDailyReplayRunResponse> days = new ArrayList<>();
        List<Long> failedTradeSetupIds = new ArrayList<>();
        int tradeCount = 0;
        int resultCount = 0;
        int successCount = 0;
        int errorCount = 0;
        int skippedCount = 0;
        for (LocalDate date = resolvedFrom; !date.isAfter(resolvedTo); date = date.plusDays(1)) {
            if (isWeekend(date)) {
                continue;
            }
            BacktestDailyReplayRunResponse daily = runForDateInternal(date);
            days.add(daily);
            tradeCount += valueOrZero(daily.getTradeCount());
            resultCount += valueOrZero(daily.getResultCount());
            successCount += valueOrZero(daily.getSuccessCount());
            errorCount += valueOrZero(daily.getErrorCount());
            skippedCount += valueOrZero(daily.getSkippedCount());
            if (daily.getFailedTradeSetupIds() != null) {
                failedTradeSetupIds.addAll(daily.getFailedTradeSetupIds());
            }
        }

        return BacktestDailyReplayRangeRunResponse.builder()
                .status("success")
                .from(resolvedFrom)
                .to(resolvedTo)
                .source(ATR_SIGNAL_SOURCE)
                .dayCount(days.size())
                .tradeCount(tradeCount)
                .resultCount(resultCount)
                .successCount(successCount)
                .errorCount(errorCount)
                .skippedCount(skippedCount)
                .failedTradeSetupIds(failedTradeSetupIds.stream().distinct().toList())
                .days(days)
                .runAt(LocalDateTime.now(MARKET_ZONE))
                .build();
    }

    private BacktestDailyReplayRunResponse runForDateInternal(LocalDate tradeDate) {
        LocalDate resolvedDate = tradeDate != null ? tradeDate : previousAvailableTradeDate(LocalDate.now(MARKET_ZONE));
        LocalDateTime start = resolvedDate.atStartOfDay();
        LocalDateTime end = resolvedDate.atTime(LocalTime.MAX);
        LocalDateTime runAt = LocalDateTime.now(MARKET_ZONE);
        List<TriggeredTradeSetupEntity> trades = tradeRepository.findBySourceForBacktestDate(
                ATR_SIGNAL_SOURCE,
                start,
                end);

        int successCount = 0;
        int errorCount = 0;
        int skippedCount = 0;
        List<Long> failedTradeSetupIds = new ArrayList<>();
        for (TriggeredTradeSetupEntity trade : trades) {
            for (ReplayScenario scenario : SCENARIOS) {
                Optional<BacktestReplayResultEntity> existing = existingResultOptional(trade.getId(), scenario);
                if (existing.filter(this::isSuccessResult).isPresent()) {
                    skippedCount++;
                    continue;
                }
                BacktestReplayRequest request = request(scenario);
                try {
                    BacktestReplayResponse response = backtestReplayService.replayTrade(trade.getId(), request);
                    saveSuccess(trade, response, scenario, runAt);
                    successCount++;
                } catch (IllegalArgumentException ex) {
                    saveError(trade, scenario, ex.getMessage(), runAt);
                    errorCount++;
                    failedTradeSetupIds.add(trade.getId());
                } catch (Exception ex) {
                    saveError(trade, scenario, ex.getMessage(), runAt);
                    errorCount++;
                    failedTradeSetupIds.add(trade.getId());
                    log.warn("Backtest replay failed for trade {} interval {} policy {}.",
                            trade.getId(), scenario.interval(), scenario.resultTriggerPricePolicy(), ex);
                }
            }
        }

        return BacktestDailyReplayRunResponse.builder()
                .status("success")
                .tradeDate(resolvedDate)
                .source(ATR_SIGNAL_SOURCE)
                .tradeCount(trades.size())
                .resultCount(successCount + errorCount + skippedCount)
                .successCount(successCount)
                .errorCount(errorCount)
                .skippedCount(skippedCount)
                .failedTradeSetupIds(failedTradeSetupIds.stream().distinct().toList())
                .runAt(runAt)
                .build();
    }

    private BacktestReplayRequest request(ReplayScenario scenario) {
        BacktestReplayRequest request = new BacktestReplayRequest();
        request.setIntradayOnly(true);
        request.setInterval(scenario.interval());
        request.setTriggerPricePolicy(scenario.executionTriggerPricePolicy());
        request.setSquareOffTime(SQUARE_OFF_TIME);
        request.setReEntryOnStopLoss(scenario.reEntryOnStopLoss());
        request.setMaxReEntries(scenario.reEntryOnStopLoss() ? 1 : 0);
        return request;
    }

    private void saveSuccess(TriggeredTradeSetupEntity trade,
                             BacktestReplayResponse response,
                             ReplayScenario scenario,
                             LocalDateTime runAt) {
        BacktestReplayResultEntity result = existingResult(trade.getId(), scenario);
        populateTradeSnapshot(result, trade, runAt);
        result.setStatus("SUCCESS");
        result.setMessage(response.getMessage());
        BacktestReplayResponse.ResolvedConfig resolved = response.getResolved();
        if (resolved != null) {
            result.setTriggerPricePolicy(scenario.resultTriggerPricePolicy());
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

    private void saveError(TriggeredTradeSetupEntity trade, ReplayScenario scenario, String message, LocalDateTime runAt) {
        BacktestReplayResultEntity result = existingResult(trade.getId(), scenario);
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

    private BacktestReplayResultEntity existingResult(Long tradeSetupId, ReplayScenario scenario) {
        return existingResultOptional(tradeSetupId, scenario)
                .orElseGet(() -> BacktestReplayResultEntity.builder()
                        .tradeSetupId(tradeSetupId)
                        .interval(scenario.interval())
                        .triggerPricePolicy(scenario.resultTriggerPricePolicy())
                        .squareOffTime(SQUARE_OFF_TIME)
                        .createdAt(LocalDateTime.now(MARKET_ZONE))
                        .build());
    }

    private Optional<BacktestReplayResultEntity> existingResultOptional(Long tradeSetupId, ReplayScenario scenario) {
        return resultRepository.findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(
                tradeSetupId,
                scenario.interval(),
                scenario.resultTriggerPricePolicy(),
                SQUARE_OFF_TIME);
    }

    private boolean isSuccessResult(BacktestReplayResultEntity result) {
        return result != null && "SUCCESS".equalsIgnoreCase(result.getStatus());
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
        result.setTriggerPricePolicy(valueOrDefault(result.getTriggerPricePolicy(), TRIGGER_PRICE_POLICY));
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

    LocalDate previousAvailableTradeDate(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        while (isWeekend(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record ReplayScenario(String interval,
                                  String executionTriggerPricePolicy,
                                  String resultTriggerPricePolicy,
                                  boolean reEntryOnStopLoss) {
    }
}
