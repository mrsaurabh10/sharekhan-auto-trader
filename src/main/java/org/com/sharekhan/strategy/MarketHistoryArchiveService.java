package org.com.sharekhan.strategy;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.MarketHistoryCandleEntity;
import org.com.sharekhan.repository.MarketHistoryCandleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MarketHistoryArchiveService {
    private final MarketHistoryCandleRepository repository;

    /** Stable symbol/timestamp IDs make repeated captures update rather than duplicate bars. */
    @Transactional
    public synchronized int save(String symbol, List<StrategyCandle> candles, String provider, LocalDateTime now) {
        validateSymbol(symbol);
        Map<LocalDateTime, MarketHistoryCandleEntity> rows = new TreeMap<>();
        for (StrategyCandle candle : candles) {
            if (!valid(candle, now)) continue;
            LocalDateTime timestamp = LocalDateTime.of(candle.date(), candle.time());
            rows.put(timestamp, MarketHistoryCandleEntity.builder()
                    .id(symbol + "@" + timestamp).symbol(symbol).candleTime(timestamp)
                    .openPrice(candle.open()).highPrice(candle.high()).lowPrice(candle.low())
                    .closePrice(candle.close()).volume(candle.volume()).provider(provider).capturedAt(now).build());
        }
        // A delayed fallback must not replace prices already captured from the live feed.
        for (MarketHistoryCandleEntity existing : repository.findAllById(
                rows.values().stream().map(MarketHistoryCandleEntity::getId).toList())) {
            if (priority(existing.getProvider()) > priority(provider)) rows.remove(existing.getCandleTime());
        }
        if (!rows.isEmpty()) repository.saveAll(rows.values());
        return rows.size();
    }

    @Transactional(readOnly = true)
    public List<MarketHistoryCandleEntity> read(String symbol, LocalDate from, LocalDate to) {
        validateSymbol(symbol);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("Provide from and to dates with from <= to");
        }
        return repository.findBySymbolAndCandleTimeGreaterThanEqualAndCandleTimeLessThanOrderByCandleTime(
                symbol, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private void validateSymbol(String symbol) {
        if (!Set.of("NIFTY", "BANKNIFTY").contains(symbol)) {
            throw new IllegalArgumentException("symbol must be NIFTY or BANKNIFTY");
        }
    }

    private int priority(String provider) {
        if ("MSTOCK".equals(provider)) return 3;
        if ("LIVE_CACHE".equals(provider)) return 2;
        return 1;
    }

    private boolean valid(StrategyCandle candle, LocalDateTime now) {
        if (candle == null || candle.date() == null || candle.time() == null) return false;
        LocalTime time = candle.time();
        return !time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(15, 30))
                && time.getMinute() % 5 == 0 && time.getSecond() == 0 && time.getNano() == 0
                && !LocalDateTime.of(candle.date(), time).plusMinutes(5).isAfter(now)
                && Double.isFinite(candle.open()) && Double.isFinite(candle.high())
                && Double.isFinite(candle.low()) && Double.isFinite(candle.close())
                && candle.low() > 0 && candle.high() >= Math.max(candle.open(), candle.close())
                && candle.low() <= Math.min(candle.open(), candle.close());
    }
}
