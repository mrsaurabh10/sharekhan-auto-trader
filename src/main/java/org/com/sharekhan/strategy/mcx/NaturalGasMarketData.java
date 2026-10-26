package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.strategy.StrategyCandle;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Prior-session history plus persisted, observed current-session Sharekhan ticks. */
@Service @RequiredArgsConstructor
public class NaturalGasMarketData {
    private final SharekhanHistoricalService history;
    private final NaturalGasLiveCandles live;
    private final Map<Integer, Cached> cache=new HashMap<>();
    private record Cached(LocalDateTime loaded, List<StrategyCandle> candles) { }
    public void watch(Integer code) { live.watch(code); }
    public String status(Integer code) { return live.status(code); }
    public synchronized List<StrategyCandle> candles(Integer code, LocalDateTime now) {
        live.watch(code);
        cache.entrySet().removeIf(e->e.getValue().loaded().isBefore(now.minusDays(1)));
        var prior=cache.get(code);
        if(prior==null || !prior.loaded().isAfter(now.minusMinutes(5))) {
            var rows=history.getRecentHistoricalCandles(code,"5minute").stream()
                    .map(c->new StrategyCandle(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume()))
                    .map(NaturalGasMarketData::intervalStart)
                    .filter(c->c.time()!=null && !c.time().isBefore(NaturalGasSession.OPEN) && c.time().isBefore(NaturalGasSession.CLOSE))
                    .toList();
            if(cache.size()>=16 && !cache.containsKey(code))cache.clear();
            prior=new Cached(now,rows);cache.put(code,prior);
        }
        // A snapshot cache must never delay a newly completed live bar.
        var rows=new ArrayList<>(prior.candles());
        // History is documented as prior-session only. Never substitute it for an observed live bar.
        var observed=live.completed(code,now);
        var keys=observed.stream().map(c->LocalDateTime.of(c.date(),c.time())).collect(java.util.stream.Collectors.toSet());
        rows.removeIf(c->c.date()!=null && c.time()!=null && keys.contains(LocalDateTime.of(c.date(),c.time())));
        rows.addAll(observed);
        return List.copyOf(rows);
    }
    /** Historical tradeTime is the last trade in the bucket, not necessarily xx:x4:59. MCX scope only. */
    static StrategyCandle intervalStart(StrategyCandle c) {
        if(c.time()==null)return c;
        var time=c.time().withMinute(c.time().getMinute()/5*5).withSecond(0).withNano(0);
        return new StrategyCandle(c.date(),time,c.open(),c.high(),c.low(),c.close(),c.volume());
    }
}
