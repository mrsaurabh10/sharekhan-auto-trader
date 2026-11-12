package org.com.sharekhan.strategy.batman;

import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.dhan.DhanInstrumentResolver;
import org.com.sharekhan.util.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.json.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Read-only Greeks provider. Never logs credentials, refreshes sessions or places orders. */
@Service
public class DhanOptionChainClient {
    public record Chain(String symbol,LocalDate expiry,double spot,Instant receivedAt,List<BatmanRules.Option> options){}
    private final AccessTokenPersistenceService tokens;
    private final BrokerCredentialsRepository credentials;
    private final DhanInstrumentResolver instruments;
    private final CryptoService crypto;
    private final long account;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private Instant next=Instant.MIN;
    public DhanOptionChainClient(AccessTokenPersistenceService tokens,BrokerCredentialsRepository credentials,DhanInstrumentResolver instruments,CryptoService crypto,
            @Value("${app.stock-history.dhan-credential-id:0}") long account){this.tokens=tokens;this.credentials=credentials;this.instruments=instruments;this.crypto=crypto;this.account=account;}
    public Chain fetch(String symbol,LocalDate expiry){
        String id=instruments.indexIdentity(symbol);
        var body=new JSONObject().put("UnderlyingScrip",Long.parseLong(id)).put("UnderlyingSeg","IDX_I").put("Expiry",expiry.toString());
        return parse(symbol,expiry,request(body),Instant.now());
    }
    static Chain parse(String symbol,LocalDate expiry,JSONObject raw,Instant received){
        if(!"success".equalsIgnoreCase(raw.optString("status")))throw new IllegalStateException("Dhan option chain unavailable");
        var data=raw.getJSONObject("data");double spot=data.getDouble("last_price");var oc=data.getJSONObject("oc");List<BatmanRules.Option> list=new ArrayList<>();
        for(String strike:oc.keySet())for(String side:List.of("ce","pe")){
            var value=oc.getJSONObject(strike).optJSONObject(side);if(value==null||value.optJSONObject("greeks")==null)continue;
            list.add(new BatmanRules.Option(side.toUpperCase(Locale.ROOT),Double.parseDouble(strike),value.getJSONObject("greeks").optDouble("delta",Double.NaN),
                    value.optDouble("top_bid_price",Double.NaN),value.optDouble("top_ask_price",Double.NaN),value.optLong("top_bid_quantity",0),value.optLong("top_ask_quantity",0),value.optString("security_id",null)));
        }
        return new Chain(symbol,expiry,spot,received,List.copyOf(list));
    }
    private synchronized JSONObject request(JSONObject body){
        var now=Instant.now();if(now.isBefore(next))throw new IllegalStateException("Waiting for Dhan option-chain rate limit");next=now.plusSeconds(3);
        var owner=credentials.findById(account).filter(c->"Dhan".equalsIgnoreCase(c.getBrokerName())&&Boolean.TRUE.equals(c.getActive())).orElseThrow(()->new IllegalStateException("Configured Dhan data account is unavailable"));
        var token=tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",account).filter(t->t.getToken()!=null&&!t.getToken().isBlank()&&t.getExpiry()!=null&&t.getExpiry().isAfter(now.plusSeconds(30))).orElseThrow(()->new IllegalStateException("Reconnect the configured Dhan data session"));
        String client=crypto.decrypt(owner.getClientCode());
        if(client==null||!client.matches("[0-9]{1,20}"))throw new IllegalStateException("Configured Dhan data account identity is unavailable");
        try{
            var req=HttpRequest.newBuilder(URI.create("https://api.dhan.co/v2/optionchain")).timeout(Duration.ofSeconds(8))
                    .header("access-token",token.getToken()).header("client-id",client).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){
                if(response.statusCode()!=200)throw new IllegalStateException("Dhan option-chain HTTP "+response.statusCode()+"; waiting without entry");
                byte[] bytes=stream.readNBytes(4_000_001);if(bytes.length>4_000_000)throw new IllegalStateException("Option chain exceeds response limit");return new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Dhan option-chain read interrupted");}
        catch(java.io.IOException e){throw new IllegalStateException("Dhan option-chain connection unavailable");}
    }
}
