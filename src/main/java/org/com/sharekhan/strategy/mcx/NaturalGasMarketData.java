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
    private final NaturalGasShoonyaCandles shoonya;
    private final Map<Integer, Cached> cache=new HashMap<>();
    private record Cached(LocalDateTime loaded, List<StrategyCandle> candles) { }
    public void watch(Integer code) { live.watch(code); }
    public String status(Integer code) { return live.status(code); }
    public synchronized List<StrategyCandle> candles(Integer code, LocalDateTime now) {
        live.watch(code);
        cache.entrySet().removeIf(e->e.getValue().loaded().isBefore(now.minusDays(1)));
        var prior=cache.get(code);
        if(prior==null || !prior.loaded().isAfter(now.minusMinutes(5))) {
            var rows=history.getRecentRawHistoricalCandles(code,"5minute").stream()
                    .map(c->new StrategyCandle(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume()))
                    .map(NaturalGasMarketData::intervalStart)
                    .filter(c->c.time()!=null && !c.time().isBefore(NaturalGasSession.OPEN) && c.time().isBefore(NaturalGasSession.CLOSE))
                    .toList();
            if(cache.size()>=16 && !cache.containsKey(code))cache.clear();
            // A transient empty refresh must not discard the last verified prior-session seed.
            if(!rows.isEmpty() || prior==null)prior=new Cached(now,rows);
            else prior=new Cached(now,prior.candles());
            cache.put(code,prior);
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
    public synchronized List<StrategyCandle> shoonyaCandles(org.com.sharekhan.entity.ScriptMasterEntity future,LocalDateTime now) {
        // Source boundary is explicit: Sharekhan prior sessions, Shoonya current day only.
        var rows=new ArrayList<>(candles(future.getScripCode(),now).stream().filter(c->c.date().isBefore(now.toLocalDate())).toList());
        rows.addAll(shoonya.completed(future,now));return List.copyOf(rows);
    }
    public List<StrategyCandle> threeMinuteCandles(org.com.sharekhan.entity.ScriptMasterEntity future,LocalDateTime now) {
        return shoonya.completed(future,now,3);
    }
    public String threeMinuteStatus(Integer code){return shoonya.diagnostics(code,3).toString();}
    public String shoonyaStatus(Integer code){return shoonya.diagnostics(code).toString();}
    /** Verified (start,end] provider buckets: an exact boundary belongs to the interval ending there. */
    static StrategyCandle intervalStart(StrategyCandle c) {
        if(c.date()==null || c.time()==null)return c;
        var at=LocalDateTime.of(c.date(),c.time());
        if(at.getMinute()%5==0 && at.getSecond()==0 && at.getNano()==0)at=at.minusNanos(1);
        at=at.withMinute(at.getMinute()/5*5).withSecond(0).withNano(0);
        return new StrategyCandle(at.toLocalDate(),at.toLocalTime(),c.open(),c.high(),c.low(),c.close(),c.volume());
    }
}
