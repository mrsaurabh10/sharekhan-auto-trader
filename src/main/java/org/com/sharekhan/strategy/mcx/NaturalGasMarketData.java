package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.strategy.StrategyCandle;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Exact Sharekhan MCX futures history. No MStock exchange mapping or synthetic volume. */
@Service @RequiredArgsConstructor
public class NaturalGasMarketData {
    private final SharekhanHistoricalService history;
    private final Map<Integer, Cached> cache=new HashMap<>();
    private record Cached(LocalDateTime loaded, List<StrategyCandle> candles) { }
    public synchronized List<StrategyCandle> candles(Integer code, LocalDateTime now) {
        cache.entrySet().removeIf(e->e.getValue().loaded().isBefore(now.minusDays(1)));
        var prior=cache.get(code);
        if(prior!=null && prior.loaded().isAfter(now.minusSeconds(30))) return prior.candles();
        var rows=history.getRecentHistoricalCandles(code,"5minute").stream()
                .map(c->new StrategyCandle(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList();
        // Bound memory even if a large number of manual users select different expiries.
        if(cache.size()>=16 && !cache.containsKey(code)) cache.clear();
        cache.put(code,new Cached(now,rows));return rows;
    }
}
