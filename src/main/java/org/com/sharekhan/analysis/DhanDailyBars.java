package org.com.sharekhan.analysis;

import org.json.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Strict daily parser; identical provider copies collapse, conflicting daily prices never do. */
public final class DhanDailyBars {
    private DhanDailyBars() { }
    public record Candle(LocalDate date,long epoch,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close,long volume) { }
    public static List<Candle> parse(String body,LocalDate from,LocalDate through) {
        var data=new JSONObject(body);var stamps=data.getJSONArray("timestamp");int n=stamps.length();
        if(n<1||n>1500)throw new IllegalArgumentException("Empty or oversized daily response");
        for(String k:List.of("open","high","low","close","volume"))if(data.getJSONArray(k).length()!=n)throw new IllegalArgumentException("Daily arrays differ");
        var result=new TreeMap<LocalDate,Candle>();
        for(int i=0;i<n;i++) {
            long epoch=new BigDecimal(stamps.get(i).toString()).longValueExact();var day=Instant.ofEpochSecond(epoch).atZone(StockAnalysisService.IST).toLocalDate();
            if(day.isBefore(from)||day.isAfter(through))throw new IllegalArgumentException("Daily date outside requested range");
            var o=number(data,"open",i);var h=number(data,"high",i);var l=number(data,"low",i);var c=number(data,"close",i);
            long v=new BigDecimal(data.getJSONArray("volume").get(i).toString()).longValueExact();
            if(o.signum()<=0||c.signum()<=0||l.signum()<=0||h.compareTo(o)<0||h.compareTo(c)<0||h.compareTo(l)<0||l.compareTo(o)>0||l.compareTo(c)>0||v<0)throw new IllegalArgumentException("Invalid daily OHLCV");
            var next=new Candle(day,epoch,o,h,l,c,v);var old=result.putIfAbsent(day,next);
            if(old!=null&&!old.equals(next))throw new IllegalArgumentException("Conflicting daily candles");
        }
        if(!result.lastKey().equals(through))throw new IllegalArgumentException("Latest completed session not published");
        return List.copyOf(result.values());
    }
    private static BigDecimal number(JSONObject d,String k,int i) { return new BigDecimal(d.getJSONArray(k).get(i).toString()).setScale(8,RoundingMode.UNNECESSARY); }
}
