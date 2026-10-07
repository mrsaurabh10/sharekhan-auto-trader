package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Counts evaluated candles rather than minute-by-minute scheduler polls. Audit rows survive restart. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupertrendDecisionDiagnostics {
    private final StrategySupport support;
    private final Map<RunKey, Statistics> statistics = new ConcurrentHashMap<>();

    public void record(StrategyApplyRequest request, StrategyMetadata metadata, String symbol,
                       IndicatorSnapshot indicator, SupertrendSignalRules.Result result) {
        LocalDateTime candleTime = LocalDateTime.of(indicator.candle().date(), indicator.candle().time());
        statistics.keySet().removeIf(key -> key.day().isBefore(candleTime.toLocalDate()));
        RunKey key = new RunKey(candleTime.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), metadata.id(), symbol);
        Statistics stats = statistics.computeIfAbsent(key, ignored -> new Statistics());
        String counts = stats.record(candleTime, result);
        if (counts == null) return;
        String reason = result.reason() + " Values: close=" + support.roundPrice(indicator.candle().close())
                + ", ST=" + support.roundPrice(indicator.supertrend()) + ", RSI=" + support.roundPrice(indicator.rsi())
                + ", prevRSI=" + support.roundPrice(indicator.previousRsi()) + ", EMA50=" + support.roundPrice(indicator.ema50())
                + ", ADX=" + support.roundPrice(indicator.adx()) + ", +DI=" + support.roundPrice(indicator.plusDi())
                + ", -DI=" + support.roundPrice(indicator.minusDi()) + ". " + counts;
        log.info("SUPERTREND_EVALUATION | template={} | user={} | credentials={} | symbol={} | candle={} | outcome={} | {}",
                metadata.id(), request.getUserId(), request.getBrokerCredentialsId(), symbol, candleTime,
                result.passed() ? "QUALIFIED" : "REJECTED", reason);
        support.auditStrategy(request, metadata, symbol, "STRATEGY_EVALUATION", result.passed() ? "QUALIFIED" : "REJECTED",
                "Candle=" + candleTime + ". " + reason, null, null);
    }

    private record RunKey(LocalDate day, Long userId, Long credentialsId, String templateId, String symbol) { }

    private static class Statistics {
        private LocalDateTime lastCandle;
        private int evaluated;
        private int rejected;
        private final Map<String, Integer> failures = new LinkedHashMap<>();

        synchronized String record(LocalDateTime candle, SupertrendSignalRules.Result result) {
            if (lastCandle != null && !candle.isAfter(lastCandle)) return null;
            lastCandle = candle;
            evaluated++;
            if (!result.passed()) rejected++;
            for (SupertrendSignalRules.Failure failure : result.failures()) failures.merge(failure.rule(), 1, Integer::sum);
            return "Daily evaluated=" + evaluated + ", rejected=" + rejected + ", rejectionCounts=" + failures;
        }
    }
}
