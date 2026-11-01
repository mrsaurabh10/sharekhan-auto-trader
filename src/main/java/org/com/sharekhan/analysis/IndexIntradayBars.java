package org.com.sharekhan.analysis;

import java.time.*;import java.math.*;import java.util.*;import org.json.*;

/** Strict completed-session validation. Never resamples, fills holes or repairs source prices. */
public final class IndexIntradayBars {
    public record Candle(long epoch,LocalDateTime time,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,Long volume) { }
    public static List<Candle> parse(String raw,int interval,IndexHistorySessions.Window window) {
        if(!Set.of(1,5).contains(interval))throw new IllegalArgumentException("Unsupported interval");
        var json=new JSONObject(raw);var times=json.getJSONArray("timestamp");int n=times.length();if(n>2000)throw new IllegalArgumentException("Oversized response");
        for(String k:List.of("open","high","low","close"))if(json.getJSONArray(k).length()!=n)throw new IllegalArgumentException("Array mismatch");
        var volumes=json.optJSONArray("volume");if(volumes!=null&&volumes.length()!=n)throw new IllegalArgumentException("Volume mismatch");
        var rows=new TreeMap<LocalDateTime,Candle>();
        for(int i=0;i<n;i++) {
            long epoch=new BigDecimal(times.get(i).toString()).longValueExact();var time=LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch),StockAnalysisService.IST);
            if(!time.toLocalDate().equals(window.day())||time.getSecond()!=0)throw new IllegalArgumentException("Unexpected timestamp");
            if(time.toLocalTime().isBefore(window.start())||!time.toLocalTime().isBefore(window.end()))continue;
            if(Duration.between(window.start(),time.toLocalTime()).toMinutes()%interval!=0)throw new IllegalArgumentException("Unexpected interval alignment");
            var o=num(json,"open",i);var h=num(json,"high",i);var l=num(json,"low",i);var c=num(json,"close",i);
            Long v=volumes==null||volumes.isNull(i)?null:new BigDecimal(volumes.get(i).toString()).longValueExact();
            if(l.signum()<=0||o.signum()<=0||c.signum()<=0||h.compareTo(o)<0||h.compareTo(c)<0||h.compareTo(l)<0||l.compareTo(o)>0||l.compareTo(c)>0||(v!=null&&v<0))throw new IllegalArgumentException("Invalid source OHLCV");
            var bar=new Candle(epoch,time,o,h,l,c,v);var previous=rows.putIfAbsent(time,bar);if(previous!=null&&!previous.equals(bar))throw new IllegalArgumentException("Conflicting duplicate timestamp");
        }
        if(rows.size()!=window.minutes()/interval)throw new IllegalArgumentException("Incomplete session");
        for(int i=0;i<window.minutes()/interval;i++)if(!rows.containsKey(window.day().atTime(window.start()).plusMinutes((long)i*interval)))throw new IllegalArgumentException("Missing session bar");
        return List.copyOf(rows.values());
    }
    private static BigDecimal num(JSONObject j,String k,int i){return new BigDecimal(j.getJSONArray(k).get(i).toString()).stripTrailingZeros();}
    public static void verifyPair(List<Candle> one,List<Candle> five) {
        var minute=new HashMap<LocalDateTime,Candle>();one.forEach(b->minute.put(b.time(),b));var tolerance=new BigDecimal("0.01");
        for(var f:five){Candle first=minute.get(f.time()),last=minute.get(f.time().plusMinutes(4));if(first==null||last==null)throw new IllegalArgumentException("Missing minute window");
            var high=first.high();var low=first.low();for(int i=1;i<5;i++){var b=minute.get(f.time().plusMinutes(i));if(b==null)throw new IllegalArgumentException("Missing minute window");high=high.max(b.high());low=low.min(b.low());}
            if(first.open().subtract(f.open()).abs().compareTo(tolerance)>0||high.subtract(f.high()).abs().compareTo(tolerance)>0||low.subtract(f.low()).abs().compareTo(tolerance)>0||last.close().subtract(f.close()).abs().compareTo(tolerance)>0)throw new IllegalArgumentException("Native intervals disagree");
        }
    }
    private IndexIntradayBars() { }
}
