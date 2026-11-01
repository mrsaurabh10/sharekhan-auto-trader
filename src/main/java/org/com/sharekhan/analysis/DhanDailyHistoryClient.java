package org.com.sharekhan.analysis;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.dhan.DhanInstrumentResolver;
import java.time.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.math.BigDecimal;
import org.json.JSONObject;

/** Historical reads only; uses one explicitly configured account and never initiates login or orders. */
@Service
public class DhanDailyHistoryClient {
    private final AccessTokenPersistenceService tokens;
    private final BrokerCredentialsRepository credentials;
    private final DhanInstrumentResolver instruments;
    private final long credentialId;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NEVER).build();
    private long lastStart;
    public DhanDailyHistoryClient(AccessTokenPersistenceService tokens,BrokerCredentialsRepository credentials,DhanInstrumentResolver instruments,
            @Value("${app.stock-history.dhan-credential-id:0}") long credentialId) {this.tokens=tokens;this.credentials=credentials;this.instruments=instruments;this.credentialId=credentialId;}
    public record Input(String securityId,BigDecimal tick,List<DhanDailyBars.Candle> candles) { }
    public static class Unavailable extends RuntimeException {
        public final boolean stopRun;
        public Unavailable(String code,boolean stopRun){super(code);this.stopRun=stopRun;}
    }
    public synchronized Input fetch(StockDailyHistoryStore.Member member,StockDailyHistoryStore.Saved saved,LocalDate from,LocalDate through) throws InterruptedException {
        var token=historicalToken();
        try {
            var identity=instruments.cashIdentity("NC",member.symbol());var instrument=identity.instrument();
            if(!member.isin().equals(identity.isin())||saved.securityId()!=null&&!saved.securityId().equals(instrument.securityId()))throw new Unavailable("Instrument identity changed; review the saved mapping.",false);
            long wait=1_100_000_000L-(System.nanoTime()-lastStart);if(wait>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);lastStart=System.nanoTime();
            var body=new JSONObject().put("securityId",instrument.securityId()).put("exchangeSegment","NSE_EQ").put("instrument","EQUITY").put("expiryCode",0).put("oi",false).put("fromDate",from.toString()).put("toDate",through.plusDays(1).toString());
            var response=request(token.getToken(),body.toString());
            return new Input(instrument.securityId(),instrument.tickSize(),DhanDailyBars.parse(response,from,through));
        }catch(Unavailable e){throw e;}catch(DhanInstrumentResolver.MasterUnavailable e){throw new Unavailable("Dhan instrument master unavailable; saved history retained.",true);}catch(java.io.IOException e){throw new Unavailable("Dhan historical connection unavailable; saved history retained.",true);}catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        catch(Exception ignored){throw new Unavailable("Daily response unavailable or failed validation; saved history retained.",false);}
    }
    private org.com.sharekhan.entity.AccessTokenEntity historicalToken() {
        if(credentialId<=0)throw new Unavailable("Configure the Dhan historical-data account.",true);
        var owner=credentials.findById(credentialId).orElseThrow(()->new Unavailable("Configured Dhan account is unavailable.",true));
        if(!"Dhan".equalsIgnoreCase(owner.getBrokerName())||!Boolean.TRUE.equals(owner.getActive()))throw new Unavailable("Configured Dhan account is inactive.",true);
        // AccessTokenPersistenceService decrypts storage. Never pass database ciphertext to Dhan.
        var token=tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",credentialId).orElseThrow(()->new Unavailable("Dhan session is unavailable; reconnect the configured account.",true));
        if(token.getExpiry()==null||!token.getExpiry().isAfter(Instant.now().plusSeconds(60))||token.getToken()==null||token.getToken().isBlank())throw new Unavailable("Dhan session expired; reconnect the configured account.",true);
        return token;
    }
    /** Uses the same account and rate limiter as stock EOD reads. No login or orders. */
    public synchronized String fetchIndex(String symbol,LocalDate day,int interval) throws InterruptedException {
        if(!Set.of("NIFTY","BANKNIFTY").contains(symbol)||!Set.of(1,5).contains(interval)||day==null)throw new IllegalArgumentException("Unsupported index request");
        var token=historicalToken();
        try {
            String id=instruments.indexIdentity(symbol),expected=symbol.equals("NIFTY")?"13":"25";
            if(!expected.equals(id))throw new Unavailable("Index master identity changed; review the saved mapping.",true);
            long wait=1_100_000_000L-(System.nanoTime()-lastStart);if(wait>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);lastStart=System.nanoTime();
            var body=new JSONObject().put("securityId",id).put("exchangeSegment","IDX_I").put("instrument","INDEX").put("interval",Integer.toString(interval)).put("oi",false).put("fromDate",day+" 00:00:00").put("toDate",day.plusDays(1)+" 00:00:00");
            String raw=requestIntraday(token.getToken(),body.toString());var parsed=new JSONObject(raw);
            for(String key:List.of("timestamp","open","high","low","close"))if(parsed.optJSONArray(key)==null)throw new Unavailable("Index history response is not candle data; update will retry later.",true);
            return raw;
        }catch(Unavailable e){throw e;}catch(DhanInstrumentResolver.MasterUnavailable e){throw new Unavailable("Dhan instrument master unavailable; saved index history retained.",true);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}catch(Exception e){throw new Unavailable("Index historical response unavailable; update will retry later.",true);}
    }
    String requestIntraday(String token,String body)throws Exception{return performRequest("/charts/intraday",token,body);}
    String request(String token,String body) throws Exception {return performRequest("/charts/historical",token,body);}
    private String performRequest(String path,String token,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://api.dhan.co/v2"+path)).timeout(Duration.ofSeconds(25)).header("access-token",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()==401||response.statusCode()==403)throw new Unavailable("Dhan authentication or Data API access failed; reconnect/check the configured data plan.",true);
            if(response.statusCode()==429)throw new Unavailable("Dhan data rate limit reached; update will retry later.",true);
            if(response.statusCode()!=200)throw new Unavailable("Dhan historical data unavailable; update will retry later.",true);
            byte[] bytes=stream.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IllegalArgumentException();
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
