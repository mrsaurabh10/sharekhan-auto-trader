package org.com.sharekhan.strategy.mcx;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.com.sharekhan.strategy.StrategyCandle;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.auth.ShoonyaSavedSession;
import org.json.*;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Simulator candle input only. Read-only broker calls, no login/refresh/order dependencies. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasShoonyaCandles {
    private static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final ShoonyaProperties properties;
    private final AccessTokenRepository tokens;
    private final CryptoService crypto;
    private final ShoonyaInstrumentMasterService master;
    private final StrategyCandleHistoryRepository repository;
    private final Map<String,State> states=new HashMap<>();
    private static class State {
        LocalDate day; LocalDateTime attemptedAt,boundary; String verifiedIdentity,problem="Not fetched";
        List<StrategyCandle> rows=List.of(); Map<LocalDateTime,StrategyCandle> persisted=new HashMap<>();
        String instrumentKey; int forming;
    }
    public synchronized List<StrategyCandle> completed(ScriptMasterEntity future,LocalDateTime now) {
        return completed(future,now,5);
    }
    public synchronized List<StrategyCandle> completed(ScriptMasterEntity future,LocalDateTime now,int interval) {
        requireInterval(interval);
        int code=Objects.requireNonNull(future.getScripCode());
        String cacheKey=code+":"+interval;
        states.entrySet().removeIf(e->!now.toLocalDate().equals(e.getValue().day));
        if(states.size()>=32&&!states.containsKey(cacheKey))throw new IllegalStateException("MCX candle cache capacity reached");
        var state=states.computeIfAbsent(cacheKey,k->{var s=new State();s.day=now.toLocalDate();return s;});
        var boundary=now.withMinute(now.getMinute()/interval*interval).withSecond(0).withNano(0);
        if(state.attemptedAt!=null && state.attemptedAt.isAfter(now.minusSeconds(30)) && boundary.equals(state.boundary))
            return state.problem==null?state.rows:List.of();
        state.attemptedAt=now;state.boundary=boundary;
        try {
            var instrument=master.resolveNaturalGasFuture(future,now);
            String session=existingSession(now);
            String identity=instrument.getToken()+":"+instrument.getTradingSymbol();
            if(!identity.equals(state.verifiedIdentity)) {
                var raw=post("GetSecurityInfo",new JSONObject().put("exch","MCX").put("token",instrument.getToken()),session);
                verifyIdentity(new JSONObject(raw),instrument);state.verifiedIdentity=identity;
            }
            var request=new JSONObject().put("exch","MCX").put("token",instrument.getToken())
                .put("st",String.valueOf(now.toLocalDate().atTime(9,0).atZone(IST).toEpochSecond()))
                .put("et",String.valueOf(now.atZone(IST).toEpochSecond())).put("intrv",String.valueOf(interval));
            var parsed=parse(post("TPSeries",request,session),now,interval);
            String key=(interval==5?"SHOONYA_MCX_TPS:":"SHOONYA_MCX_TPS_3M:")+instrument.getToken()+":"+NaturalGasContracts.expiry(future);
            if(!key.equals(state.instrumentKey)) {
                state.persisted.clear();state.instrumentKey=key;
                for(var saved:repository.findTop1000ByInstrumentKeyOrderByCandleTimeDesc(key))
                    if(saved.getCandleTime().toLocalDate().equals(now.toLocalDate()))state.persisted.put(saved.getCandleTime(),candle(saved));
            }
            var changed=new ArrayList<StrategyCandleHistoryEntity>();
            for(var c:parsed.completed()) {
                var at=LocalDateTime.of(c.date(),c.time());
                if(!c.equals(state.persisted.get(at)))changed.add(StrategyCandleHistoryEntity.builder().id(key+"@"+at)
                    .instrumentKey(key).candleTime(at).openPrice(c.open()).highPrice(c.high()).lowPrice(c.low()).closePrice(c.close()).volume(c.volume()).build());
            }
            if(!changed.isEmpty())repository.saveAll(changed);
            repository.deleteByInstrumentKeyAndCandleTimeBefore(key,now.minusDays(14));
            state.persisted.clear();for(var c:parsed.completed())state.persisted.put(LocalDateTime.of(c.date(),c.time()),c);
            state.rows=parsed.completed();state.forming=parsed.forming();state.problem=null;
            log.info("MCX_SHOONYA_CANDLES | scrip={} symbol={} completed={} formingExcluded={} changed={} latest={} interval={}",
                code,instrument.getTradingSymbol(),state.rows.size(),state.forming,changed.size(),state.rows.getLast().time(),interval);
            return state.rows;
        } catch(Exception invalid) {
            // No provider body, UID, credential or exception message is logged/exposed.
            state.problem=invalid instanceof ShoonyaSavedSession.Unavailable ? invalid.getMessage()
                    : "Shoonya candle fetch/identity/validation unavailable ("+invalid.getClass().getSimpleName()+")";
            log.warn("MCX_SHOONYA_CANDLES_WAIT | scrip={} type={} interval={}",code,invalid.getClass().getSimpleName(),interval);
            return List.of();
        }
    }
    private String existingSession(LocalDateTime now) {
        // OAuth expiry is a configured local estimate. Test today's saved session at the broker;
        // never extend it in storage, re-login, or select another user's credentials.
        var saved=ShoonyaSavedSession.currentGlobal(tokens.findAllByBrokerName("Shoonya"),now,
                properties.getAccessTokenTtlSeconds()).orElseThrow(ShoonyaSavedSession.Unavailable::new);
        return Objects.requireNonNull(crypto.decrypt(saved.getToken()));
    }
    String post(String endpoint,JSONObject request,String session)throws Exception {
        if(!Set.of("GetSecurityInfo","TPSeries").contains(endpoint))throw new IllegalArgumentException("Read-only endpoint required");
        var base=URI.create(properties.getApiUrl());
        if(!"https".equals(base.getScheme()) || !"api.shoonya.com".equals(base.getHost()))throw new IllegalStateException("Official Shoonya HTTPS endpoint required");
        if(properties.getUid()==null||properties.getUid().isBlank())throw new IllegalStateException("Shoonya UID unavailable");
        var connection=(HttpURLConnection)URI.create(properties.getApiUrl()+"/"+endpoint).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
        connection.setRequestProperty("Authorization","Bearer "+session);
        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        request.put("uid",properties.getUid());
        try {
            try(var out=connection.getOutputStream()){out.write(("jData="+request).getBytes(StandardCharsets.UTF_8));}
            if(connection.getResponseCode()!=200)throw new IllegalStateException("Shoonya HTTP response unavailable");
            try(var in=connection.getInputStream()) {
                var bytes=in.readNBytes(2000001);if(bytes.length>2000000)throw new IllegalStateException("Shoonya response too large");
                return new String(bytes,StandardCharsets.UTF_8);
            }
        } finally {connection.disconnect();}
    }
    static void verifyIdentity(JSONObject r,ShoonyaInstrumentEntity instrument) {
        if(!"Ok".equals(r.optString("stat")) || !"MCX".equals(r.optString("exch"))
            || !instrument.getToken().equals(r.optString("token")) || !instrument.getTradingSymbol().equals(r.optString("tsym"))
            || !instrument.getExpiry().equalsIgnoreCase(r.optString("exd")) || !"FUTCOM".equals(r.optString("instname"))
            || !Objects.equals(instrument.getLotSize(),r.getInt("ls")) || !Double.isFinite(r.getDouble("ti")) || r.getDouble("ti")<=0 || Math.abs(instrument.getTickSize()-r.getDouble("ti"))>1e-9)
            throw new IllegalStateException("Shoonya returned another instrument");
    }
    record Parsed(List<StrategyCandle> completed,int forming) { }
    private static void requireInterval(int interval) {
        if(interval!=3 && interval!=5)throw new IllegalArgumentException("MCX candle interval must be three or five minutes");
    }
    static Parsed parse(String body,LocalDateTime now) {return parse(body,now,5);}
    static Parsed parse(String body,LocalDateTime now,int interval) {
        requireInterval(interval);
        var raw=new JSONArray(body);if(raw.length()>2000)throw new IllegalArgumentException("Too many MCX intervals");
        var rows=new TreeMap<LocalDateTime,StrategyCandle>();int forming=0;
        for(int i=0;i<raw.length();i++) {
            var r=raw.getJSONObject(i);if(!"Ok".equals(r.optString("stat")))throw new IllegalArgumentException("Unsuccessful candle");
            var at=LocalDateTime.parse(r.getString("time"),TIME);
            if(!at.equals(LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(r.getString("ssboe"))),IST))
                || !at.toLocalDate().equals(now.toLocalDate()) || !NaturalGasSession.marketOpen(at)
                || at.getMinute()%interval!=0 || at.getSecond()!=0 || at.getNano()!=0 || at.isAfter(now))throw new IllegalArgumentException("Invalid candle clock");
            if(at.plusMinutes(interval).isAfter(now)){forming++;continue;}
            double o=r.getDouble("into"),h=r.getDouble("inth"),l=r.getDouble("intl"),c=r.getDouble("intc");
            long v=new java.math.BigDecimal(r.get("intv").toString()).longValueExact();
            if(!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)||l<=0
                || l>Math.min(o,c)||h<Math.max(o,c)||v<0)throw new IllegalArgumentException("Invalid candle OHLCV");
            if(rows.put(at,new StrategyCandle(at.toLocalDate(),at.toLocalTime(),o,h,l,c,v))!=null)throw new IllegalArgumentException("Duplicate candle");
        }
        if(rows.isEmpty())throw new IllegalArgumentException("No completed current-day candles");
        return new Parsed(List.copyOf(rows.values()),forming);
    }
    private static StrategyCandle candle(StrategyCandleHistoryEntity r){return new StrategyCandle(r.getCandleTime().toLocalDate(),r.getCandleTime().toLocalTime(),r.getOpenPrice(),r.getHighPrice(),r.getLowPrice(),r.getClosePrice(),r.getVolume());}
    public synchronized Map<String,Object> diagnostics(Integer code) {return diagnostics(code,5);}
    public synchronized Map<String,Object> diagnostics(Integer code,int interval) {
        requireInterval(interval);
        var out=new LinkedHashMap<String,Object>();out.put("provider","SHOONYA_TPS");out.put("intervalMinutes",interval);var state=states.get(code+":"+interval);
        if(state==null){out.put("status","Not fetched");return out;}
        out.put("status",state.problem==null?"ok":state.problem);out.put("attemptedAt",state.attemptedAt);
        out.put("instrumentKey",state.instrumentKey);out.put("completedCount",state.rows.size());out.put("formingExcluded",state.forming);
        out.put("first",state.rows.isEmpty()?null:LocalDateTime.of(state.rows.getFirst().date(),state.rows.getFirst().time()));
        out.put("latestEnd",state.rows.isEmpty()?null:LocalDateTime.of(state.rows.getLast().date(),state.rows.getLast().time()).plusMinutes(interval));return out;
    }
}
