package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.dhan.DhanInstrumentResolver;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Greeks only. No authentication, account changes or orders. Missing data uses the agreed 0.5. */
@Service
public class AdxDmiGreeks {
    public record Reading(double delta,Double gamma,Double vega,Double theta,String source,Instant at) {
        public static Reading fallback(String reason){return new Reading(.5,null,null,null,"FALLBACK_0.5:"+reason,Instant.now());}
    }
    private record Chain(Instant attempted,JSONObject data,String failure) { }
    private final AccessTokenPersistenceService tokens;
    private final BrokerCredentialsRepository accounts;
    private final DhanInstrumentResolver instruments;
    private final CryptoService crypto;
    private final long credentialId;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private Instant lastRequest=Instant.EPOCH;
    private final Map<String,Chain> chains=new HashMap<>();
    public AdxDmiGreeks(AccessTokenPersistenceService tokens,BrokerCredentialsRepository accounts,DhanInstrumentResolver instruments,CryptoService crypto,
            @Value("${app.strategy.adx-dmi.dhan-credential-id:${app.stock-history.dhan-credential-id:0}}") long credentialId){
        this.tokens=tokens;this.accounts=accounts;this.instruments=instruments;this.crypto=crypto;this.credentialId=credentialId;
    }
    private final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    public synchronized Reading read(ScriptMasterEntity spot,ScriptMasterEntity option){
        try {
            if(credentialId<=0)return Reading.fallback("NO_DATA_ACCOUNT");
            var account=accounts.findById(credentialId).orElse(null);
            if(account==null || !Boolean.TRUE.equals(account.getActive()) || !"Dhan".equalsIgnoreCase(account.getBrokerName()))return Reading.fallback("DATA_ACCOUNT_UNAVAILABLE");
            var token=tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",credentialId).orElse(null);
            if(token==null || token.getToken()==null || token.getToken().isBlank() || token.getExpiry()==null || !token.getExpiry().isAfter(Instant.now().plusSeconds(5)))return Reading.fallback("SESSION_UNAVAILABLE");
            String client=crypto.decrypt(account.getClientCode());
            if(client==null || !client.matches("[0-9]{1,20}"))return Reading.fallback("CLIENT_ID_UNAVAILABLE");
            if(!instruments.hasCurrentSnapshot()){
                if(warming.compareAndSet(false,true))java.util.concurrent.CompletableFuture.runAsync(()->{
                    try{instruments.underlyingIdentity(spot.getExchange(),spot.getTradingSymbol());}catch(RuntimeException ignored){ }finally{warming.set(false);}
                });
                return Reading.fallback("MASTER_WARMING");
            }
            var underlying=instruments.underlyingIdentity(spot.getExchange(),spot.getTradingSymbol());
            var trade=TriggeredTradeSetupEntity.builder().symbol(option.getTradingSymbol()).exchange(option.getExchange()).expiry(option.getExpiry()).strikePrice(option.getStrikePrice()).optionType(option.getOptionType()).build();
            var contract=instruments.resolve(trade);
            if(!Objects.equals(option.getLotSize(),contract.lotSize()))return Reading.fallback("CONTRACT_LOT_MISMATCH");
            LocalDate expiry=LocalDate.parse(option.getExpiry(),java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            String key=underlying.segment()+":"+underlying.securityId()+":"+expiry;
            var cached=chains.get(key);var now=Instant.now();
            if(cached==null || !cached.attempted().plusSeconds(3).isAfter(now)){
                if(lastRequest.plusSeconds(3).isAfter(now))return Reading.fallback("DATA_RATE_LIMIT");
                lastRequest=now;
                try {
                    var body=new JSONObject().put("UnderlyingScrip",Long.parseLong(underlying.securityId())).put("UnderlyingSeg",underlying.segment()).put("Expiry",expiry.toString());
                    cached=new Chain(now,new JSONObject(request(token.getToken(),client,body.toString())),null);
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Reading.fallback("INTERRUPTED");}
                catch(Exception unavailable){cached=new Chain(now,null,"API_UNAVAILABLE");}
                chains.put(key,cached);
                chains.entrySet().removeIf(e->e.getValue().attempted().isBefore(now.minusSeconds(60)));
            }
            if(cached.data()==null)return Reading.fallback(cached.failure());
            return parse(cached.data(),option,contract.securityId(),cached.attempted(),Instant.now());
        }catch(RuntimeException unavailable){return Reading.fallback("IDENTITY_OR_DATA_UNAVAILABLE");}
    }
    String request(String token,String client,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("https://api.dhan.co/v2/optionchain")).timeout(Duration.ofSeconds(2))
                .header("access-token",token).header("client-id",client).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()){
            if(response.statusCode()!=200)throw new IllegalStateException("DATA_HTTP_"+response.statusCode());
            byte[] bytes=stream.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IllegalStateException("DATA_TOO_LARGE");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static Reading parse(JSONObject raw,ScriptMasterEntity option,String expectedSecurity,Instant at,Instant now){
        try{
            if(at==null || at.isAfter(now) || at.isBefore(now.minusSeconds(5)) || !"success".equals(raw.optString("status")))return Reading.fallback("CHAIN_STALE_OR_FAILED");
            var oc=raw.getJSONObject("data").getJSONObject("oc");JSONObject row=null;
            for(String strike:oc.keySet())if(Math.abs(Double.parseDouble(strike)-option.getStrikePrice())<1e-7){if(row!=null)return Reading.fallback("AMBIGUOUS_STRIKE");row=oc.getJSONObject(strike);}
            if(row==null)return Reading.fallback("STRIKE_UNAVAILABLE");
            var contract=row.getJSONObject("CE".equals(option.getOptionType())?"ce":"pe");
            if(!expectedSecurity.equals(contract.get("security_id").toString()))return Reading.fallback("CONTRACT_ID_MISMATCH");
            var greeks=contract.getJSONObject("greeks");double delta=greeks.getDouble("delta");
            if(!Double.isFinite(delta) || ("CE".equals(option.getOptionType())?delta<=0 || delta>1:delta>=0 || delta< -1))return Reading.fallback("DELTA_INVALID");
            return new Reading(Math.abs(delta),optional(greeks,"gamma"),optional(greeks,"vega"),optional(greeks,"theta"),"DHAN_API",at);
        }catch(RuntimeException malformed){return Reading.fallback("CHAIN_INVALID");}
    }
    private static Double optional(JSONObject o,String key){double n=o.optDouble(key,Double.NaN);return Double.isFinite(n)?n:null;}
}
