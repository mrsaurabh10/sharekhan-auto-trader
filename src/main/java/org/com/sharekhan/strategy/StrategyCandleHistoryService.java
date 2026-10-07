package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.StrategyCandleHistoryEntity;
import org.com.sharekhan.repository.StrategyCandleHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class StrategyCandleHistoryService {
    static final int MAX_CANDLES = 250;
    private final StrategyCandleHistoryRepository repository;

    /** Serialize updates within this process; instrument/timestamp IDs make repeated polls idempotent. */
    @Transactional
    public synchronized List<StrategyCandle> mergeAndSave(String instrumentKey, List<StrategyCandle> incoming,
                                                         LocalDateTime now) {
        Map<LocalDateTime, StrategyCandle> stored = new TreeMap<>();
        for (StrategyCandleHistoryEntity row : repository.findTop250ByInstrumentKeyOrderByCandleTimeDesc(instrumentKey)) {
            StrategyCandle candle = new StrategyCandle(row.getCandleTime().toLocalDate(), row.getCandleTime().toLocalTime(),
                    row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice(), row.getVolume());
            if (usable(candle, now)) stored.put(row.getCandleTime(), candle);
        }
        Map<LocalDateTime, StrategyCandle> merged = new TreeMap<>(stored);
        for (StrategyCandle candle : incoming) {
            if (usable(candle, now)) merged.put(LocalDateTime.of(candle.date(), candle.time()), candle);
        }
        List<StrategyCandle> result = new ArrayList<>(merged.values());
        if (result.size() > MAX_CANDLES) result = new ArrayList<>(result.subList(result.size() - MAX_CANDLES, result.size()));

        List<StrategyCandleHistoryEntity> changed = new ArrayList<>();
        for (StrategyCandle candle : result) {
            LocalDateTime timestamp = LocalDateTime.of(candle.date(), candle.time());
            if (!candle.equals(stored.get(timestamp))) {
                changed.add(StrategyCandleHistoryEntity.builder()
                        .id(instrumentKey + "@" + timestamp).instrumentKey(instrumentKey).candleTime(timestamp)
                        .openPrice(candle.open()).highPrice(candle.high()).lowPrice(candle.low())
                        .closePrice(candle.close()).volume(candle.volume()).build());
            }
        }
        if (!changed.isEmpty()) repository.saveAll(changed);
        LocalDateTime cutoff = result.isEmpty() ? now.minusDays(14)
                : LocalDateTime.of(result.get(0).date(), result.get(0).time());
        repository.deleteByInstrumentKeyAndCandleTimeBefore(instrumentKey, cutoff);
        return List.copyOf(result);
    }

    static boolean usable(StrategyCandle candle, LocalDateTime now) {
        if (candle == null || candle.date() == null || candle.time() == null
                || candle.time().isBefore(LocalTime.of(9, 15)) || !candle.time().isBefore(LocalTime.of(15, 30))
                || candle.time().getMinute() % 5 != 0 || candle.time().getSecond() != 0
                || candle.date().isBefore(now.toLocalDate().minusDays(14))) return false;
        return !LocalDateTime.of(candle.date(), candle.time()).plusMinutes(StrategySupport.CANDLE_MINUTES).isAfter(now)
                && Double.isFinite(candle.open()) && Double.isFinite(candle.high())
                && Double.isFinite(candle.low()) && Double.isFinite(candle.close())
                && candle.low() > 0d && candle.high() >= Math.max(candle.open(), candle.close())
                && candle.low() <= Math.min(candle.open(), candle.close());
    }
}
