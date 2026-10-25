package org.com.sharekhan.service;

import org.com.sharekhan.util.TradeSide;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.dhan.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.SpotAtrPreviousDayBigTradePlusSellStrategy;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.springframework.stereotype.Service;

/** Display only: never saves booked P&L, changes trade state, refreshes tokens or submits orders. */
@Service @RequiredArgsConstructor
public class RunningTradePnlService {
    private final LtpCacheService prices;
    private final BrokerCredentialsRepository brokers;
    private final TriggeredTradeSetupRepository trades;
    private final AccessTokenPersistenceService tokens;
    private final CryptoService crypto;
    private final DhanOrderClient client;
    private static final Set<TriggeredTradeStatus> OPEN=EnumSet.of(TriggeredTradeStatus.EXECUTED,
        TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_ORDER_PLACED);
    private static final Set<TriggeredTradeStatus> EXPOSURE=EnumSet.of(TriggeredTradeStatus.EXECUTED,
        TriggeredTradeStatus.TARGET_ORDER_PLACED,TriggeredTradeStatus.EXIT_ORDER_PLACED,
        TriggeredTradeStatus.EXIT_TRIGGERED,TriggeredTradeStatus.EXIT_FAILED,
        TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,TriggeredTradeStatus.ENTRY_SUBMITTING,TriggeredTradeStatus.TRIGGERED);
    public record Snapshot(BigDecimal price,BigDecimal runningPnl,Instant observedAt,String source) { }
    private record Remembered(String identity,Snapshot snapshot) { }
    private record Positions(String token,Instant at,String body) {
        @Override public String toString(){return "DhanPositionSnapshot[redacted]";}
    }
    private final Map<Long,Remembered> marks=new ConcurrentHashMap<>();
    private final Map<Long,Positions> positions=new ConcurrentHashMap<>();
    private Clock clock=Clock.systemUTC();

    public Optional<Snapshot> snapshot(TriggeredTradeSetupEntity trade) {
        if(trade==null || trade.getId()==null || trade.getStatus()==null || !OPEN.contains(trade.getStatus())
            || trade.getQuantity()==null || trade.getQuantity()<=0 || !positive(trade.getActualEntryPrice())) return Optional.empty();
        String identity=Arrays.asList(trade.getAppUserId(),trade.getBrokerCredentialsId(),trade.getOrderId(),
            trade.getExchange(),trade.getSymbol(),trade.getExpiry(),trade.getStrikePrice(),trade.getOptionType(),
            trade.getQuantity(),trade.getActualEntryPrice(),trade.getEntryAction(),trade.getSource(),trade.getBrokerExecutionProduct()).toString();
        Remembered saved=marks.get(trade.getId());
        Snapshot result=saved!=null && saved.identity().equals(identity)?saved.snapshot():null;
        if(trade.getScripCode()!=null) {
            Double price=prices.getLtp(trade.getScripCode());
            LocalDateTime at=prices.getObservedAt(trade.getScripCode());
            if(positive(price) && at!=null) {
                Instant time=at.atZone(ZoneId.of("Asia/Kolkata")).toInstant();
                if(!time.isAfter(clock.instant()) && (result==null || time.isAfter(result.observedAt())))
                    result=mark(trade,BigDecimal.valueOf(price),time,"Last known price");
            }
        }
        if(result==null || !result.observedAt().isAfter(clock.instant().minusSeconds(15))) {
            Snapshot broker=dhanPosition(trade);
            if(broker!=null && (result==null || !broker.observedAt().isBefore(result.observedAt()))) result=broker;
        }
        if(result!=null && (marks.size()<10_000 || marks.containsKey(trade.getId())))
            marks.put(trade.getId(),new Remembered(identity,result));
        return Optional.ofNullable(result);
    }
    private Snapshot mark(TriggeredTradeSetupEntity trade,BigDecimal price,Instant at,String source) {
        BigDecimal pnl=price.subtract(BigDecimal.valueOf(trade.getActualEntryPrice())).multiply(BigDecimal.valueOf(trade.getQuantity()));
        if(TradeSide.isShort(trade)) pnl=pnl.negate();
        return new Snapshot(price,pnl.setScale(2,RoundingMode.HALF_UP),at,source);
    }
    private Snapshot dhanPosition(TriggeredTradeSetupEntity trade) {
        try {
            if(trade.getBrokerCredentialsId()==null || trade.getAppUserId()==null) return null;
            var broker=brokers.findById(trade.getBrokerCredentialsId()).orElseThrow();
            if(!"Dhan".equalsIgnoreCase(broker.getBrokerName()) || !Objects.equals(broker.getAppUserId(),trade.getAppUserId())) return null;
            if(trades.countOpenContract(trade.getBrokerCredentialsId(),trade.getExchange(),trade.getSymbol(),
                trade.getExpiry(),trade.getOptionType(),trade.getStrikePrice(),EXPOSURE)!=1) return null;
            String account=crypto.decrypt(broker.getClientCode());
            var token=tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",broker.getId()).orElseThrow();
            if(token.getToken()==null || token.getToken().isBlank() || token.getExpiry()==null || !token.getExpiry().isAfter(clock.instant())) return null;
            Positions data=positions.compute(broker.getId(),(id,old)->{
                if(old!=null && old.token().equals(token.getToken()) && old.at().isAfter(clock.instant().minusSeconds(10))) return old;
                try {
                    var response=client.exchange("GET","/positions",token.getToken(),null);
                    return new Positions(token.getToken(),clock.instant(),response.ok()?response.body():"[]");
                } catch(Exception ignored){return new Positions(token.getToken(),clock.instant(),"[]");}
            });
            JSONArray rows=new JSONArray(data.body());JSONObject match=null;
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(!account.equals(row.optString("dhanClientId")) || !DhanInstrumentResolver.segment(trade.getExchange()).equals(row.optString("exchangeSegment"))) continue;
                String symbol=row.optString("tradingSymbol").toUpperCase(Locale.ROOT);
                if(trade.getOptionType()!=null) {
                    if(!symbol.startsWith(trade.getSymbol().toUpperCase(Locale.ROOT)+"-")) continue;
                    LocalDate expiry=LocalDate.parse(trade.getExpiry(),DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT));
                    if(!row.getString("drvExpiryDate").startsWith(expiry.toString())) continue;
                    if(new BigDecimal(row.get("drvStrikePrice").toString()).compareTo(BigDecimal.valueOf(trade.getStrikePrice()))!=0) continue;
                    String option="CE".equalsIgnoreCase(trade.getOptionType())?"CALL":"PUT";
                    if(!option.equalsIgnoreCase(row.getString("drvOptionType"))) continue;
                } else if(!symbol.equalsIgnoreCase(trade.getSymbol())) continue;
                String product=trade.getBrokerExecutionProduct();
                if(product==null) product=Boolean.TRUE.equals(trade.getIntraday())?"INTRADAY":trade.getOptionType()!=null?"MARGIN":"CNC";
                if(!product.equals(row.optString("productType"))) continue;
                if(match!=null) return null;
                match=row;
            }
            boolean shortSide=TradeSide.isShort(trade);
            if(match==null || match.getBigDecimal("netQty").compareTo(BigDecimal.valueOf(trade.getQuantity()).multiply(BigDecimal.valueOf(TradeSide.sign(trade))))!=0
                || match.getBigDecimal(shortSide ? "buyQty" : "sellQty").signum()!=0
                || match.getBigDecimal(shortSide ? "sellQty" : "buyQty").compareTo(BigDecimal.valueOf(trade.getQuantity()))!=0
                || !(shortSide ? "SHORT" : "LONG").equals(match.getString("positionType"))
                || new BigDecimal(match.get(shortSide ? "sellAvg" : "buyAvg").toString()).compareTo(BigDecimal.valueOf(trade.getActualEntryPrice()))!=0
                || match.optDouble("multiplier",1d)!=1d) return null;
            BigDecimal pnl=new BigDecimal(match.get("unrealizedProfit").toString());
            BigDecimal price=BigDecimal.valueOf(trade.getActualEntryPrice()).add(pnl.multiply(BigDecimal.valueOf(TradeSide.sign(trade))).divide(BigDecimal.valueOf(trade.getQuantity()),12,RoundingMode.HALF_UP));
            if(price.signum()<=0) return null;
            return new Snapshot(price.stripTrailingZeros(),pnl.setScale(2,RoundingMode.HALF_UP),data.at(),"Dhan position snapshot");
        } catch(Exception ignored){return null;}
    }
    private static boolean positive(Double value){return value!=null && Double.isFinite(value) && value>0;}
}
