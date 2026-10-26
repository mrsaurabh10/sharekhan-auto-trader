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
    public record Tick(JsonNode data, Instant receivedAt, String messageTimestamp) {
        public Tick(JsonNode data,Instant receivedAt){this(data,receivedAt,null);}
    }
    public record StreamInterrupted() { }
    @EventListener
    public synchronized void interrupted(StreamInterrupted event) {
        watched.values().forEach(s->{invalidate(s,"STREAM_INTERRUPTED");s.previousAt=null;s.previousQuantity=null;s.bar=null;});
    }
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final StrategyCandleHistoryRepository repository;
    private final Map<Integer, State> watched = new HashMap<>();
    private static class State {
        LocalDate day;
        Instant receivedAt;
        String problem="No valid Sharekhan MCX observations yet";
        final Map<String,Long> rejections=new TreeMap<>();
        final Deque<Map<String,String>> samples=new ArrayDeque<>();
        Instant lastFrameReceivedAt;
        long maxProviderGapSeconds;
        long completedCount;
        long accountedQuietGaps;
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
        String invalidReason;
        Bar(LocalDateTime start, double price, boolean valid) {
            this.start=start;open=high=low=close=price;this.valid=valid;this.invalidReason=valid?null:"PARTIAL_START";
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
    public void onTick(Tick tick) { record(tick.data(),tick.receivedAt(),tick.messageTimestamp()); }

    synchronized void record(JsonNode data, Instant receivedAt) { record(data,receivedAt,null); }
    synchronized void record(JsonNode data, Instant receivedAt, String messageTimestamp) {
        if (data == null || receivedAt == null || !"MX".equalsIgnoreCase(data.path("exchangeCode").asText())) return;
        int code=data.path("scripCode").asInt(-1);
        var state=watched.get(code);
        if(state==null)return;
        state.lastFrameReceivedAt=receivedAt;
        var sample=new LinkedHashMap<String,String>();
        sample.put("receivedAt",receivedAt.toString());sample.put("messageTimestamp",bounded(messageTimestamp));
        for(String field:List.of("lastUpdatedTime","ltt","ltp","qty","ltq"))sample.put(field,bounded(data.path(field).asText()));
        state.samples.addLast(Collections.unmodifiableMap(sample));while(state.samples.size()>20)state.samples.removeFirst();
        try {
            String providerStamp=data.path("lastUpdatedTime").asText();
            if(providerStamp.isBlank())providerStamp=data.path("ltt").asText();
            var at=providerTime(providerStamp);
            var received=LocalDateTime.ofInstant(receivedAt,IST);
            if(at.isAfter(received) || at.isBefore(received.minusSeconds(15)) || !NaturalGasSession.marketOpen(at)) {
                state.problem="Rejected stale/future/out-of-session provider timestamp "+at;
                invalidate(state,"STALE_OR_FUTURE_PROVIDER_TIMESTAMP");
                return;
            }
            double price=data.path("ltp").asDouble(Double.NaN);
            if(!Double.isFinite(price)||price<=0 || !data.hasNonNull("qty")){invalidate(state,"MISSING_PRICE_OR_VOLUME");return;}
            long quantity=new java.math.BigDecimal(data.get("qty").asText()).longValueExact();
            if(quantity<0){invalidate(state,"NEGATIVE_VOLUME");return;}
            if(!at.toLocalDate().equals(state.day)) {
                state.day=at.toLocalDate();state.previousAt=null;state.previousQuantity=null;state.bar=null;
                state.completed.entrySet().removeIf(e->e.getKey().isBefore(at.minusDays(14)));
            }
            if(state.previousAt!=null && at.isBefore(state.previousAt)){state.rejections.merge("OUT_OF_ORDER",1L,Long::sum);return;}
            // Identical timestamp/quantity is a duplicate snapshot, not a trade.
            if(at.equals(state.previousAt) && Objects.equals(state.previousQuantity,quantity))return;
            var start=at.withMinute(at.getMinute()/5*5).withSecond(0).withNano(0);
            if(state.previousAt!=null)state.maxProviderGapSeconds=Math.max(state.maxProviderGapSeconds,Duration.between(state.previousAt,at).getSeconds());
            boolean accountedGap=accountsForQuietGap(data,state,at,quantity,price);
            boolean continuous=state.previousAt!=null && quantity>=state.previousQuantity
                    && (!state.previousAt.isBefore(at.minusSeconds(15)) || accountedGap);
            if(accountedGap && state.previousAt.isBefore(at.minusSeconds(15)))state.accountedQuietGaps++;
            if(state.bar!=null && !start.equals(state.bar.start)) {
                if(!continuous)invalidate(state,"BOUNDARY_GAP_OR_COUNTER_RESET");
                if(!at.isBefore(start.plusSeconds(16)) && !accountedGap)invalidate(state,"LATE_BOUNDARY");
                finish(code,state,start,accountedGap);
                state.bar=null;
            }
            if(state.bar==null) {
                // Need observations on both sides of the boundary. A late subscription cannot backfill.
                boolean full=continuous && state.previousAt.isBefore(start) && (at.isBefore(start.plusSeconds(16)) || accountedGap);
                state.bar=new Bar(start,price,full);
                // First interval may seed from today's session OHLC and cumulative volume within 15s of open.
                if(start.toLocalTime().equals(NaturalGasSession.OPEN) && state.previousAt==null && at.isBefore(start.plusSeconds(16))) {
                    double open=data.path("open").asDouble(Double.NaN),high=data.path("high").asDouble(Double.NaN),low=data.path("low").asDouble(Double.NaN);
                    if(Double.isFinite(open)&&Double.isFinite(high)&&Double.isFinite(low)&&low>0&&low<=Math.min(open,price)&&high>=Math.max(open,price)) {
                        state.bar.open=open;state.bar.high=high;state.bar.low=low;state.bar.volume=quantity;state.bar.valid=true;state.bar.invalidReason=null;
                    }
                } else if(full) state.bar.volume=quantity-state.previousQuantity;
            } else {
                if(!continuous)invalidate(state,quantity<state.previousQuantity?"COUNTER_RESET":"SYMBOL_OBSERVATION_GAP");
                if(Objects.equals(state.previousQuantity,quantity) && price!=state.bar.close)invalidate(state,"PRICE_CHANGED_WITHOUT_VOLUME");
                state.bar.high=Math.max(state.bar.high,price);state.bar.low=Math.min(state.bar.low,price);state.bar.close=price;
                if(continuous)state.bar.volume=Math.addExact(state.bar.volume,quantity-state.previousQuantity);
            }
            if(state.receivedAt==null)log.info("MCX_LIVE_CANDLES | scrip={} firstObservation={} cumulativeQty={}",code,at,quantity);
            state.previousAt=at;state.previousQuantity=quantity;state.receivedAt=receivedAt;state.problem=null;
        } catch(RuntimeException invalid) {
            // A malformed or ambiguous broker observation cannot extend a complete bar.
            invalidate(state,"INVALID_OBSERVATION_OR_PERSISTENCE");
            state.problem="Invalid live observation ("+invalid.getClass().getSimpleName()+"): lastUpdatedTime="
                    +data.path("lastUpdatedTime").asText().replace('\r',' ').replace('\n',' ').substring(0,Math.min(64,data.path("lastUpdatedTime").asText().length()))
                    +", qty="+data.path("qty").asText().substring(0,Math.min(24,data.path("qty").asText().length()));
        }
    }
    /** Cumulative volume proves quiet time only when every new unit is the current last trade.
     * No inference from elapsed time, a healthy socket, or unchanged endpoint prices alone. */
    private static boolean accountsForQuietGap(JsonNode data,State state,LocalDateTime at,long quantity,double price) {
        if(state.previousAt==null || state.previousQuantity==null || quantity<state.previousQuantity)return false;
        long delta=quantity-state.previousQuantity;
        String lastTrade=data.path("ltt").asText();
        if(lastTrade.isBlank())return false;
        try {
            var tradedAt=providerTime(lastTrade);
            // An unchanged counter proves no trades, provided the price and trade clock also did not move.
            if(delta==0)return state.bar!=null && price==state.bar.close && !tradedAt.isAfter(state.previousAt);
            if(!tradedAt.equals(at))return false;
            // One cumulative contract cannot hide an intermediate positive-quantity execution.
            if(!data.hasNonNull("ltq"))return delta==1;
            long lastQuantity=new java.math.BigDecimal(data.path("ltq").asText()).longValueExact();
            return lastQuantity>0 && lastQuantity==delta;
        } catch(RuntimeException invalid){return false;}
    }
    public synchronized String status(Integer code) {
        var state=watched.get(code);
        if(state==null)return "Live capture not armed";
        return "live last observation="+state.previousAt+", cumulative qty="+state.previousQuantity
                +(state.problem==null?"":", "+state.problem);
    }
    private void finish(int code, State state, LocalDateTime nextStart, boolean accountedGap) {
        var b=state.bar;
        if(!b.start.plusMinutes(5).equals(nextStart))invalidate(state,"MISSING_INTERVAL");
        if(state.previousAt.isBefore(nextStart.minusSeconds(15)) && !accountedGap)invalidate(state,"STALE_AT_CLOSE");
        if(!b.valid) {
            state.rejections.merge("DROPPED_BAR:"+b.invalidReason,1L,Long::sum);
            log.info("MCX_LIVE_CANDLE_REJECTED | scrip={} start={} reason={}",code,b.start,b.invalidReason);
            return;
        }
        var candle=new StrategyCandle(b.start.toLocalDate(),b.start.toLocalTime(),b.open,b.high,b.low,b.close,b.volume);
        repository.save(StrategyCandleHistoryEntity.builder().id(key(code)+"@"+b.start).instrumentKey(key(code)).candleTime(b.start)
                .openPrice(b.open).highPrice(b.high).lowPrice(b.low).closePrice(b.close).volume(b.volume).build());
        state.completed.put(b.start,candle);state.completedCount++;
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
    public synchronized Map<String,Object> diagnostics(Integer code) {
        var state=watched.get(code);var out=new LinkedHashMap<String,Object>();out.put("scripCode",code);out.put("armed",state!=null);
        if(state==null)return out;
        out.put("lastFrameReceivedAt",state.lastFrameReceivedAt);out.put("lastAcceptedProviderTime",state.previousAt);
        out.put("lastCumulativeQuantity",state.previousQuantity);out.put("maxProviderGapSeconds",state.maxProviderGapSeconds);
        out.put("accountedQuietGaps",state.accountedQuietGaps);
        out.put("completedSinceStart",state.completedCount);out.put("loadedCompletedCount",state.completed.size());
        out.put("rejections",new TreeMap<>(state.rejections));out.put("samples",List.copyOf(state.samples));
        out.put("currentBarStart",state.bar==null?null:state.bar.start);out.put("currentBarInvalidReason",state.bar==null?null:state.bar.invalidReason);
        return out;
    }
    private static void invalidate(State state,String reason) {
        state.rejections.merge(reason,1L,Long::sum);
        if(state.bar!=null){state.bar.valid=false;if(state.bar.invalidReason==null)state.bar.invalidReason=reason;}
    }
    private static String bounded(String value){return value==null?"":value.replace('\r',' ').replace('\n',' ').substring(0,Math.min(value.length(),64));}
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
