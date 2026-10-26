package org.com.sharekhan.strategy.mcx;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.StrategyCandleHistoryEntity;
import org.com.sharekhan.repository.StrategyCandleHistoryRepository;
import org.com.sharekhan.strategy.StrategyCandle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Observed Sharekhan futures ticks only: never fill a disconnect, partial bar or missing volume. */
@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class NaturalGasLiveCandles {
    public record Tick(JsonNode data, Instant receivedAt) { }
    public record StreamInterrupted() { }
    @EventListener
    public synchronized void interrupted(StreamInterrupted event) {
        watched.values().forEach(s->{s.previousAt=null;s.previousQuantity=null;s.bar=null;});
    }
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final StrategyCandleHistoryRepository repository;
    private final Map<Integer, State> watched = new HashMap<>();
    private static class State {
        LocalDate day;
        Instant receivedAt;
        String problem="No valid Sharekhan MCX observations yet";
        LocalDateTime previousAt;
        Long previousQuantity;
        Bar bar;
        final TreeMap<LocalDateTime, StrategyCandle> completed = new TreeMap<>();
    }
    private static class Bar {
        LocalDateTime start;
        double open, high, low, close;
        long volume;
        boolean valid;
        Bar(LocalDateTime start, double price, boolean valid) {
            this.start=start;open=high=low=close=price;this.valid=valid;
        }
    }
    public synchronized void watch(Integer code) {
        if (code == null || watched.containsKey(code)) return;
        // Only the exact futures resolved by the strategy may enter the collector.
        if (watched.size() >= 16) return;
        var state=new State();
        for (var row:repository.findTop1000ByInstrumentKeyOrderByCandleTimeDesc(key(code))) {
            state.completed.put(row.getCandleTime(),new StrategyCandle(row.getCandleTime().toLocalDate(),row.getCandleTime().toLocalTime(),
                    row.getOpenPrice(),row.getHighPrice(),row.getLowPrice(),row.getClosePrice(),row.getVolume()));
        }
        watched.put(code,state);
    }
    @EventListener
    public void onTick(Tick tick) { record(tick.data(),tick.receivedAt()); }

    synchronized void record(JsonNode data, Instant receivedAt) {
        if (data == null || receivedAt == null || !"MX".equalsIgnoreCase(data.path("exchangeCode").asText())) return;
        int code=data.path("scripCode").asInt(-1);
        var state=watched.get(code);
        if(state==null)return;
        try {
            String providerStamp=data.path("lastUpdatedTime").asText();
            if(providerStamp.isBlank())providerStamp=data.path("ltt").asText();
            var at=providerTime(providerStamp);
            var received=LocalDateTime.ofInstant(receivedAt,IST);
            if(at.isAfter(received) || at.isBefore(received.minusSeconds(15)) || !NaturalGasSession.marketOpen(at)) {
                state.problem="Rejected stale/future/out-of-session provider timestamp "+at;
                if(state.bar!=null)state.bar.valid=false;
                return;
            }
            double price=data.path("ltp").asDouble(Double.NaN);
            if(!Double.isFinite(price)||price<=0 || !data.hasNonNull("qty")){if(state.bar!=null)state.bar.valid=false;return;}
            long quantity=new java.math.BigDecimal(data.get("qty").asText()).longValueExact();
            if(quantity<0){if(state.bar!=null)state.bar.valid=false;return;}
            if(!at.toLocalDate().equals(state.day)) {
                state.day=at.toLocalDate();state.previousAt=null;state.previousQuantity=null;state.bar=null;
                state.completed.entrySet().removeIf(e->e.getKey().isBefore(at.minusDays(14)));
            }
            if(state.previousAt!=null && at.isBefore(state.previousAt))return;
            // Identical timestamp/quantity is a duplicate snapshot, not a trade.
            if(at.equals(state.previousAt) && Objects.equals(state.previousQuantity,quantity))return;
            var start=at.withMinute(at.getMinute()/5*5).withSecond(0).withNano(0);
            boolean continuous=state.previousAt!=null && !state.previousAt.isBefore(at.minusSeconds(15))
                    && quantity>=state.previousQuantity;
            if(state.bar!=null && !start.equals(state.bar.start)) {
                if(continuous && at.isBefore(start.plusSeconds(16))) finish(code,state,start);
                state.bar=null;
            }
            if(state.bar==null) {
                // Need observations on both sides of the boundary. A late subscription cannot backfill.
                boolean full=continuous && state.previousAt.isBefore(start) && at.isBefore(start.plusSeconds(16));
                state.bar=new Bar(start,price,full);
                // First interval may seed from today's session OHLC and cumulative volume within 15s of open.
                if(start.toLocalTime().equals(NaturalGasSession.OPEN) && state.previousAt==null && at.isBefore(start.plusSeconds(16))) {
                    double open=data.path("open").asDouble(Double.NaN),high=data.path("high").asDouble(Double.NaN),low=data.path("low").asDouble(Double.NaN);
                    if(Double.isFinite(open)&&Double.isFinite(high)&&Double.isFinite(low)&&low>0&&low<=Math.min(open,price)&&high>=Math.max(open,price)) {
                        state.bar.open=open;state.bar.high=high;state.bar.low=low;state.bar.volume=quantity;state.bar.valid=true;
                    }
                } else if(full) state.bar.volume=quantity-state.previousQuantity;
            } else {
                if(!continuous || (Objects.equals(state.previousQuantity,quantity) && price!=state.bar.close))state.bar.valid=false;
                state.bar.high=Math.max(state.bar.high,price);state.bar.low=Math.min(state.bar.low,price);state.bar.close=price;
                if(continuous)state.bar.volume=Math.addExact(state.bar.volume,quantity-state.previousQuantity);
            }
            if(state.receivedAt==null)log.info("MCX_LIVE_CANDLES | scrip={} firstObservation={} cumulativeQty={}",code,at,quantity);
            state.previousAt=at;state.previousQuantity=quantity;state.receivedAt=receivedAt;state.problem=null;
        } catch(RuntimeException invalid) {
            // A malformed or ambiguous broker observation cannot extend a complete bar.
            if(state.bar!=null)state.bar.valid=false;
            state.problem="Invalid live observation ("+invalid.getClass().getSimpleName()+"): lastUpdatedTime="
                    +data.path("lastUpdatedTime").asText().replace('\r',' ').replace('\n',' ').substring(0,Math.min(64,data.path("lastUpdatedTime").asText().length()))
                    +", qty="+data.path("qty").asText().substring(0,Math.min(24,data.path("qty").asText().length()));
        }
    }
    public synchronized String status(Integer code) {
        var state=watched.get(code);
        if(state==null)return "Live capture not armed";
        return "live last observation="+state.previousAt+", cumulative qty="+state.previousQuantity
                +(state.problem==null?"":", "+state.problem);
    }
    private void finish(int code, State state, LocalDateTime nextStart) {
        var b=state.bar;
        if(!b.valid || !b.start.plusMinutes(5).equals(nextStart) || state.previousAt.isBefore(nextStart.minusSeconds(15)))return;
        var candle=new StrategyCandle(b.start.toLocalDate(),b.start.toLocalTime(),b.open,b.high,b.low,b.close,b.volume);
        repository.save(StrategyCandleHistoryEntity.builder().id(key(code)+"@"+b.start).instrumentKey(key(code)).candleTime(b.start)
                .openPrice(b.open).highPrice(b.high).lowPrice(b.low).closePrice(b.close).volume(b.volume).build());
        state.completed.put(b.start,candle);
        log.info("MCX_LIVE_CANDLE | scrip={} start={} open={} high={} low={} close={} volume={}",code,b.start,b.open,b.high,b.low,b.close,b.volume);
        while(state.completed.size()>1000)state.completed.pollFirstEntry();
        repository.deleteByInstrumentKeyAndCandleTimeBefore(key(code),nextStart.minusDays(14));
    }
    public synchronized List<StrategyCandle> completed(Integer code, LocalDateTime now) {
        var state=watched.get(code);
        if(state==null)return List.of();
        return state.completed.entrySet().stream().filter(e->!e.getKey().plusMinutes(5).isAfter(now)
                && !e.getKey().isBefore(now.minusDays(14))).map(Map.Entry::getValue).toList();
    }
    static LocalDateTime providerTime(String value) {
        value=value.trim();
        if(value.matches("[0-9]{10}|[0-9]{13}")) {
            long epoch=Long.parseLong(value);
            return LocalDateTime.ofInstant(value.length()==13?Instant.ofEpochMilli(epoch):Instant.ofEpochSecond(epoch),IST);
        }
        // Verified production feed: 10/26/2026; historical tradeDate uses the opposite order.
        try {return LocalDateTime.parse(value,TIME);}catch(java.time.format.DateTimeParseException ignored){}
        try {return LocalDateTime.parse(value,DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT));}catch(java.time.format.DateTimeParseException ignored){}
        try {return java.time.OffsetDateTime.parse(value).atZoneSameInstant(IST).toLocalDateTime();}catch(java.time.format.DateTimeParseException ignored){}
        return LocalDateTime.parse(value.replace(' ','T'),DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
    private static String key(int code){return "SHAREKHAN_MCX_LIVE:"+code;}
}
