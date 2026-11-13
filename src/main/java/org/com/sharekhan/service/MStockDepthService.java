package org.com.sharekhan.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, demand-driven type-A full-depth feed. Never authenticates or places orders. */
@Slf4j @Service
public class MStockDepthService implements WebSocket.Listener {
    private record Route(int code,String exchange,long wireToken,String key) { }
    private final TokenStoreService tokens;
    private final CryptoService crypto;
    private final MStockInstrumentResolver resolver;
    private final MStockInstrumentRepository master;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Long,Route> routes=new ConcurrentHashMap<>();
    private final Map<Long,Instant> demanded=new ConcurrentHashMap<>();
    private final Map<Integer,QuoteCacheService.QuoteSnapshot> books=new ConcurrentHashMap<>();
    private final ByteArrayOutputStream fragments=new ByteArrayOutputStream();
    private volatile WebSocket socket;
    private volatile boolean connecting;
    private volatile Instant retryAfter=Instant.EPOCH;
    private volatile String availability="Not requested";

    public MStockDepthService(TokenStoreService tokens,CryptoService crypto,MStockInstrumentResolver resolver,MStockInstrumentRepository master){
        this.tokens=tokens;this.crypto=crypto;this.resolver=resolver;this.master=master;
    }
    public String availability(){return availability;}
    public void watch(ScriptMasterEntity script){
        if(script==null||script.getScripCode()==null)return;
        String exchange="NF".equals(script.getExchange())?"NFO":"BF".equals(script.getExchange())?"BFO":"";
        if(exchange.isEmpty())return;
        var key=resolver.resolveInstrumentKey(script.getScripCode()).orElse(null);
        if(key==null||!key.startsWith(exchange+":"))return;
        var instrument=master.findByInstrumentKey(key).orElse(null);
        if(instrument==null||instrument.getInstrumentToken()==null||instrument.getInstrumentToken()<1
                ||!exchange.equals(instrument.getExchange())||!Objects.equals(script.getLotSize(),instrument.getLotSize())
                ||!Objects.equals(script.getStrikePrice(),instrument.getStrike())
                ||script.getOptionType()==null||!key.endsWith(script.getOptionType())
                ||!Objects.equals(script.getExpiry(),displayExpiry(instrument.getExpiry())))return;
        long token=instrument.getInstrumentToken();
        var route=new Route(script.getScripCode(),script.getExchange(),token,key);
        var previous=routes.putIfAbsent(token,route);
        if(previous!=null&&!previous.equals(route))return;
        demanded.put(token,Instant.now());
        if(previous==null&&socket!=null)subscribe(socket,List.of(token));
        connectIfNeeded();
    }
    private static String displayExpiry(String expiry){
        try{return LocalDate.parse(expiry).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));}
        catch(RuntimeException invalid){return null;}
    }
    public QuoteCacheService.QuoteSnapshot book(int code){return books.get(code);}
    private synchronized void connectIfNeeded(){
        if(socket!=null||connecting||Instant.now().isBefore(retryAfter)||routes.isEmpty())return;
        var info=tokens.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        if(info==null||info.getToken()==null||info.getApiKey()==null){availability="No existing mStock depth session";retryAfter=Instant.now().plusSeconds(30);return;}
        String apiKey;
        try{apiKey=crypto.decrypt(info.getApiKey());}catch(RuntimeException plain){apiKey=info.getApiKey();}
        if(apiKey==null||apiKey.isBlank()){availability="No mStock depth API key";retryAfter=Instant.now().plusSeconds(30);return;}
        connecting=true;
        String access=info.getToken();
        URI uri=URI.create("wss://ws.mstock.trade?API_KEY="+encode(apiKey)+"&ACCESS_TOKEN="+encode(access));
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri,this)
                .orTimeout(7,TimeUnit.SECONDS).whenComplete((ws,error)->{
                    synchronized(this){
                        connecting=false;
                        if(error!=null){failed();return;}
                        socket=ws;availability="Connected; waiting for full-depth packets";
                        ws.sendText("LOGIN:"+access,true).thenRun(()->subscribe(ws,List.copyOf(routes.keySet())));
                    }
                });
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private void subscribe(WebSocket ws,List<Long> ids){
        ws.sendText(new JSONObject().put("a","subscribe").put("v",ids).toString(),true)
                .thenCompose(w->ws.sendText(new JSONObject().put("a","mode").put("v",List.of("full",ids)).toString(),true))
                .exceptionally(error->{failed();return null;});
    }
    private synchronized void failed(){
        var previous=socket;socket=null;connecting=false;retryAfter=Instant.now().plusSeconds(30);
        books.clear();availability="mStock full-depth connection unavailable; retrying after 30 seconds";
        if(previous!=null)previous.abort();
        // Exceptions can include the authenticated URI. Never log their messages.
        log.warn("MSTOCK_DEPTH_UNAVAILABLE | retrySeconds=30");
    }
    @Override public void onOpen(WebSocket ws){ws.request(1);}
    @Override public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer data,boolean last){
        try{
            synchronized(fragments){
                if(fragments.size()+data.remaining()>1_048_576){fragments.reset();throw new IllegalArgumentException();}
                byte[] part=new byte[data.remaining()];data.get(part);fragments.writeBytes(part);
                if(last){byte[] packet=fragments.toByteArray();fragments.reset();accept(packet,Instant.now());}
            }
        }catch(RuntimeException invalid){log.warn("MSTOCK_DEPTH_REJECTED | reason=invalid_packet");}
        ws.request(1);return null;
    }
    @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){ws.request(1);return null;}
    @Override public CompletionStage<?> onPing(WebSocket ws,ByteBuffer message){ws.request(1);return ws.sendPong(message);}
    @Override public CompletionStage<?> onPong(WebSocket ws,ByteBuffer message){ws.request(1);return null;}
    @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){if(ws==socket)failed();return null;}
    @Override public void onError(WebSocket ws,Throwable error){if(ws==socket)failed();}

    void accept(byte[] message,Instant received){
        var input=ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN);
        if(input.remaining()<2)return;
        int count=Short.toUnsignedInt(input.getShort());
        for(int i=0;i<count;i++){
            if(input.remaining()<2)throw new IllegalArgumentException();
            int length=Short.toUnsignedInt(input.getShort());
            if(length>input.remaining())throw new IllegalArgumentException();
            var packet=input.slice().order(ByteOrder.BIG_ENDIAN);packet.limit(length);input.position(input.position()+length);
            if(length!=184&&length!=200)continue; // LTP and index packets cannot supply an option book.
            long token=Integer.toUnsignedLong(packet.getInt(0));var route=routes.get(token);if(route==null)continue;
            // Official type-A SDK uses seconds from 1980-01-01 UTC, not Unix seconds.
            Instant updated=Instant.ofEpochSecond(Integer.toUnsignedLong(packet.getInt(60))+315532800L);
            if(updated.isAfter(received)||updated.isBefore(received.minusSeconds(5)))continue;
            double bid=Integer.toUnsignedLong(packet.getInt(68))/100d,ask=Integer.toUnsignedLong(packet.getInt(128))/100d;
            double bidQty=Integer.toUnsignedLong(packet.getInt(64)),askQty=Integer.toUnsignedLong(packet.getInt(124));
            if(bid<=0||ask<bid)continue;
            books.put(route.code(),QuoteCacheService.QuoteSnapshot.builder().scripCode(route.code()).coherentBook(true)
                    .bookSource("MSTOCK_WS").bookExchange(route.exchange()).bookProviderIdentity("mstock:"+route.wireToken()+":"+route.key())
                    .bestBid(bid).bestAsk(ask).bidQuantity(bidQty).askQuantity(askQty).lastBookAt(updated).updatedAt(received).build());
            availability="Receiving full-depth packets";
        }
    }
    @Scheduled(fixedDelay=10000)
    public synchronized void maintain(){
        var cutoff=Instant.now().minusSeconds(60);List<Long> expired=new ArrayList<>();
        demanded.forEach((id,at)->{if(at.isBefore(cutoff))expired.add(id);});
        for(long id:expired){demanded.remove(id);var route=routes.remove(id);if(route!=null)books.remove(route.code());}
        if(socket!=null&&!expired.isEmpty())socket.sendText(new JSONObject().put("a","unsubscribe").put("v",expired).toString(),true);
        if(routes.isEmpty()){close();return;}
        if(socket!=null)socket.sendPing(ByteBuffer.wrap(new byte[]{1}));else connectIfNeeded();
    }
    @PreDestroy public synchronized void close(){var ws=socket;socket=null;books.clear();if(ws!=null)ws.abort();}
}
