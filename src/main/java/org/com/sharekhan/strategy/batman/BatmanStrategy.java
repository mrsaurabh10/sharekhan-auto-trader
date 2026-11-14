package org.com.sharekhan.strategy.batman;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.service.broker.BrokerServiceFactory;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRunCoordinator;
import org.com.sharekhan.dhan.DhanInstrumentResolver;
import org.com.sharekhan.util.TradeSide;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.json.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j @Component
public class BatmanStrategy implements StrategyEvaluator {
    private final ScriptMasterRepository scripts;private final BrokerCredentialsRepository credentials;private final BrokerServiceFactory brokers;
    private final DhanOptionChainClient chains;private final DhanInstrumentResolver identities;private final BatmanBooks books;
    private final BatmanBasketRepository baskets;private final BatmanBasketStore store;private final StrategyRunRepository runs;
    private final TriggeredTradeSetupRepository trades;private final TradeExecutionService execution;private final StrategyRunCoordinator coordinator;
    private final NseMarketCalendar calendar;private final StrategySupport support;private final SharedMarketDecisions shared;
    private final Clock clock;
    public BatmanStrategy(ScriptMasterRepository scripts,BrokerCredentialsRepository credentials,BrokerServiceFactory brokers,DhanOptionChainClient chains,
            DhanInstrumentResolver identities,BatmanBooks books,BatmanBasketRepository baskets,BatmanBasketStore store,StrategyRunRepository runs,
            TriggeredTradeSetupRepository trades,TradeExecutionService execution,StrategyRunCoordinator coordinator,NseMarketCalendar calendar,StrategySupport support,SharedMarketDecisions shared){
        this.scripts=scripts;this.credentials=credentials;this.brokers=brokers;this.chains=chains;this.identities=identities;this.books=books;this.baskets=baskets;this.store=store;this.runs=runs;this.trades=trades;this.execution=execution;this.coordinator=coordinator;this.calendar=calendar;this.support=support;this.shared=shared;this.clock=Clock.system(BatmanRules.IST);
    }
    @Override public StrategyMetadata metadata(){return new StrategyMetadata(BatmanRules.TEMPLATE,"Batman Ratio Spread",
            "Listed weekly or monthly expiry, 1–45 DTE nearest 45; entries from 09:20 IST with flat-entry retries until 15:00. After any completed exit, re-enter on the next trading day after all prior exposure is confirmed flat. Buy 1 lot near 20 delta and sell 2 near 16 delta per side. Carry overnight; no adjustments. Combined gross TP and equal SL use actual center credit. Base lots scale the entire basket; option-short support depends on the execution adapter.","CE+PE");}
    public void validateConfiguration(StrategyApplyRequest request){
        BatmanRules.validate(request);
        if(request.isBatmanMarketEntry()&&(request.getBatmanManualEntryDay()==null||request.getBatmanRetryOfRunId()==null))throw new IllegalArgumentException("Reviewed same-day retry is required for Simulator market entry");
        var account=credentials.findById(request.getBrokerCredentialsId()==null?-1L:request.getBrokerCredentialsId())
                .filter(c->Objects.equals(c.getAppUserId(),request.getUserId())&&Boolean.TRUE.equals(c.getActive())&&!Boolean.FALSE.equals(c.getTradingEnabled()))
                .orElseThrow(()->new IllegalArgumentException("Select your active trading account"));
        if(request.getBatmanManualEntryDay()!=null&&!"Simulator".equalsIgnoreCase(account.getBrokerName()))
            throw new IllegalArgumentException("Manual Batman time override is available only for Simulator");
        var broker=brokers.getService(account.getBrokerName());
        if(broker==null||!broker.supportsCarryOptionShorts())throw new IllegalArgumentException("The selected execution adapter does not support carry-forward option shorts");
    }
    @Override public synchronized StrategyApplyResponse apply(StrategyApplyRequest request){
        validateConfiguration(request);var now=LocalDateTime.now(clock);String symbol=request.getSymbol();
        if(!calendar.isTradingDay(now.toLocalDate())||!entryWindow(request,now))return support.waiting(metadata(),symbol,"Waiting for 09:20–15:00 IST; fresh entries require a listed expiry at 1–45 DTE");
        boolean market=request.isBatmanMarketEntry();
        boolean freshLtp=!market&&credentials.findById(request.getBrokerCredentialsId()).map(c->"Simulator".equalsIgnoreCase(c.getBrokerName())).orElse(false);
        String blocked=request.getBatmanRetryOfRunId()==null?store.entryBlockReason(request.getUserId(),request.getBrokerCredentialsId(),symbol,now.toLocalDate())
                :store.retryBlockReason(request.getUserId(),request.getBrokerCredentialsId(),symbol,now.toLocalDate(),request.getBatmanRetryOfRunId(),request.getSubscriptionId());
        if(blocked!=null)return support.waiting(metadata(),symbol,blocked);
        Set<LocalDate> ce=dates(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol,"CE"));
        ce.retainAll(dates(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol,"PE")));
        var expiry=BatmanRules.expiry(ce,now.toLocalDate(),calendar::isTradingDay);
        if(expiry.isEmpty())return support.waiting(metadata(),symbol,"No listed expiry at 1–45 DTE; waiting for the next eligible morning");
        String id=id(request.getUserId()+":"+request.getBrokerCredentialsId()+":"+symbol+":"+now.toLocalDate()+(request.getBatmanRetryOfRunId()==null?"":":manual-retry"));
        if(baskets.existsById(id))return support.waiting(metadata(),symbol,"A Batman basket was already attempted today; no same-day re-entry");
        try{
            var chain=shared.share("BATMAN_CHAIN",symbol,expiry.get().toString(),now,()->chains.fetch(symbol,expiry.get()));
            if(!chain.symbol().equals(symbol)||!chain.expiry().equals(expiry.get())||chain.receivedAt().isAfter(clock.instant())||(!market&&chain.receivedAt().isBefore(clock.instant().minusSeconds(10))))return support.waiting(metadata(),symbol,"Waiting for a fresh exact-expiry delta chain");
            var nearest=BatmanRules.select(chain.options(),chain.spot(),0,true);
            String expiryText=expiry.get().format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
            var reference=nearest.getFirst().option();
            var referenceContract=scripts.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(symbol,reference.strike(),reference.side(),expiryText)
                    .orElseThrow(()->new IllegalStateException("Selected delta strike is absent from the execution master"));
            if(referenceContract.getLotSize()==null||referenceContract.getLotSize()<1)throw new IllegalStateException("Exact option lot size required");
            var selected=BatmanRules.select(chain.options(),chain.spot(),(long)referenceContract.getLotSize()*request.getLots(),true);
            var contracts=selected.stream().map(l->scripts.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry(symbol,l.option().strike(),l.option().side(),expiryText)
                    .orElseThrow(()->new IllegalStateException("Selected delta strike is absent from the execution master"))).toList();
            if(market)books.prepareMarket(contracts);else books.prepare(contracts);
            JSONArray plan=new JSONArray();List<TriggerRequest> orders=new ArrayList<>();Integer lotSize=null;
            List<Instant> quoteTimes=new ArrayList<>();BigDecimal estimatedCredit=BigDecimal.ZERO;
            for(int i=0;i<selected.size();i++){
                var leg=selected.get(i);var o=leg.option();String text=expiry.get().format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
                var contract=contracts.get(i);
                if(!"NF".equals(contract.getExchange())||contract.getLotSize()==null||contract.getLotSize()<1||(lotSize!=null&&!lotSize.equals(contract.getLotSize())))throw new IllegalStateException("Exact matching NSE option contract/lot sizes required");
                lotSize=contract.getLotSize();
                var mapped=identities.resolve(TriggeredTradeSetupEntity.builder().symbol(symbol).exchange(contract.getExchange()).expiry(text).strikePrice(o.strike()).optionType(o.side()).build());
                if(!mapped.securityId().equals(o.securityId())||mapped.lotSize()!=lotSize)throw new IllegalStateException("Dhan Greeks and execution contract identity differ");
                long units=(long)lotSize*request.getLots()*leg.ratio();
                boolean buy="BUY".equals(leg.action());var q=market?books.marketBook(contract.getScripCode(),contract.getExchange()):freshLtp?books.simulatorPrice(contract.getScripCode(),contract.getExchange(),buy,units):books.book(contract.getScripCode(),contract.getExchange(),buy,units);
                if(market&&q==null){
                    double bid=Double.isFinite(o.bid())&&o.bid()>0?o.bid():o.ask(),ask=Double.isFinite(o.ask())&&o.ask()>0?o.ask():o.bid();
                    if(Double.isFinite(bid)&&bid>0&&Double.isFinite(ask)&&ask>0)q=org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot.builder()
                            .scripCode(contract.getScripCode()).bookSource("DHAN_CHAIN_SIM_MARKET").bookExchange(contract.getExchange()).bookProviderIdentity(o.securityId())
                            .bestBid(bid).bestAsk(ask).lastBookAt(chain.receivedAt()).updatedAt(chain.receivedAt()).build();
                }
                if(q==null||!market&&(!BatmanBooks.valid(q,clock.instant())||!freshLtp&&((buy?q.getAskQuantity():q.getBidQuantity())==null||(buy?q.getAskQuantity():q.getBidQuantity())<units)))throw new IllegalStateException("Waiting for "+o.strike()+o.side()+": "+books.reason(contract.getScripCode()));
                if(!market&&!freshLtp&&(q.getBestAsk()-q.getBestBid())/q.getBestAsk()>.05)throw new IllegalStateException("Option spread exceeds 5%; waiting");
                quoteTimes.add(q.getLastBookAt());double premium=buy?q.getBestAsk():q.getBestBid();
                estimatedCredit=estimatedCredit.add(BigDecimal.valueOf(premium).multiply(BigDecimal.valueOf(units)).multiply(BigDecimal.valueOf(buy?-1:1)));
                var order=new TriggerRequest();order.setInstrument(symbol);order.setExchange(contract.getExchange());order.setStrikePrice(o.strike());order.setOptionType(o.side());order.setExpiry(text);
                order.setUserId(request.getUserId());order.setBrokerCredentialsId(request.getBrokerCredentialsId());order.setAction(leg.action());order.setQuantity(request.getLots()*leg.ratio());order.setLots(order.getQuantity());
                order.setIntraday(false);order.setTslEnabled(false);order.setEntryPrice(premium);order.setSource(BatmanRules.PREFIX+id+":"+i);
                order.setStrategyDecision("Batman "+expiry.get()+"; delta="+o.delta()+"; DhanSecurityId="+o.securityId()+"; no adjustments; basket credit TP/SL"+(freshLtp?"; SIMULATED fresh LTP/current quote fill; no displayed-depth guarantee":"")+(market?"; USER-APPROVED SIMULATED MARKET ENTRY: size/price guards waived; latest or admitted observed premiums":""));orders.add(order);
                plan.put(new JSONObject().put("code",contract.getScripCode()).put("exchange",contract.getExchange()).put("side",o.side()).put("strike",o.strike()).put("action",leg.action()).put("quantity",units).put("delta",o.delta()).put("dhanSecurityId",o.securityId()).put("marketEntry",market).put("freshLtpEntry",freshLtp).put("priceSource",q.getBookSource()).put("admittedPremium",premium).put("observedAt",q.getLastBookAt().toString()));
            }
            if(estimatedCredit.signum()<=0)throw new IllegalStateException("Center credit is not positive after executable spreads");
            if(!market&&(chain.receivedAt().isBefore(clock.instant().minusSeconds(10))||Duration.between(Collections.min(quoteTimes),Collections.max(quoteTimes)).compareTo(Duration.ofSeconds(2))>0||Collections.min(quoteTimes).isBefore(clock.instant().minusSeconds(5))||!shared.executionFresh()||!entryWindow(request,LocalDateTime.now(clock))))throw new IllegalStateException("Basket quote preparation elapsed; waiting for a synchronized snapshot");
            String runId=coordinator.ensureCurrentRun();if(runId==null)throw new IllegalStateException("Server-owned strategy run is required");
            var basket=BatmanBasket.builder().id(id).appUserId(request.getUserId()).brokerCredentialsId(request.getBrokerCredentialsId()).symbol(symbol).expiry(expiry.get()).strategyRunId(runId)
                    .claimedAt(LocalDateTime.now(clock)).baseLots(request.getLots()).lotSize(lotSize).planJson(plan.toString()).state("ENTERING").message("Immutable daily claim; entering long legs first").build();
            if(!(request.getBatmanRetryOfRunId()==null?store.claim(basket):store.claim(basket,request.getBatmanRetryOfRunId())))return support.waiting(metadata(),symbol,"Basket entry is already claimed or prior exposure/cooldown is unresolved");
            TriggerTradeRequestEntity first=null;boolean completed=false;
            try{
                for(int i=0;i<orders.size();i++){
                    if(!entryWindow(request,LocalDateTime.now(clock)))throw new IllegalStateException("Entry window elapsed");
                    var placed=execution.executeTriggeredTrade(orders.get(i));if(first==null)first=placed;
                    var actual=members(basket); // Never sell before both long fills are confirmed; never resubmit an uncertain order.
                    if(placed==null||actual.size()!=i+1||!matches(basket,actual,false))throw new IllegalStateException("Basket entry not fully confirmed; unwinding rather than resubmitting");
                }
                arm(basket,members(basket));completed=true;
            }catch(RuntimeException e){abort(basket,"Incomplete basket entry; cancel and close confirmed exposure");log.warn("Batman basket {} entry aborted: {}",id,e.getMessage());}
            return StrategyApplyResponse.builder().templateId(metadata().id()).symbol(symbol).direction("CE+PE").status(completed?"triggered":"error").message(completed?"Four confirmed Batman legs; actual center-credit TP and equal SL armed":"Basket entry incomplete; standard cancellation and exit recovery are active").tradeRequest(first).build();
        }catch(IllegalArgumentException|IllegalStateException waiting){return support.waiting(metadata(),symbol,waiting.getMessage());}
    }
    public synchronized StrategyApplyResponse resumeMarket(StrategyApplyRequest request){
        validateConfiguration(request);var now=LocalDateTime.now(clock);
        if(!request.isBatmanMarketEntry()||request.getBatmanRetryOfRunId()==null||!calendar.isTradingDay(now.toLocalDate())||!entryWindow(request,now))throw new IllegalArgumentException("Current reviewed Simulator market basket required");
        var basket=store.resumeNoOrder(request.getUserId(),request.getBrokerCredentialsId(),request.getSymbol(),request.getSubscriptionId(),now.toLocalDate(),request.getBatmanRetryOfRunId());
        if(basket==null)return support.waiting(metadata(),request.getSymbol(),"No eligible unfilled reviewed basket to resume");
        return coordinator.evaluate(request,()->{
            TriggerTradeRequestEntity first=null;
            try{
                var plan=new JSONArray(basket.getPlanJson());
                for(int i=0;i<plan.length();i++){
                    var leg=plan.getJSONObject(i);var order=new TriggerRequest();order.setInstrument(basket.getSymbol());order.setExchange(leg.getString("exchange"));
                    order.setStrikePrice(leg.getDouble("strike"));order.setOptionType(leg.getString("side"));order.setExpiry(basket.getExpiry().format(DateTimeFormatter.ofPattern("dd/MM/uuuu")));
                    order.setUserId(basket.getAppUserId());order.setBrokerCredentialsId(basket.getBrokerCredentialsId());order.setAction(leg.getString("action"));
                    order.setQuantity(Math.toIntExact(leg.getLong("quantity")/basket.getLotSize()));order.setLots(order.getQuantity());order.setIntraday(false);order.setTslEnabled(false);
                    order.setEntryPrice(leg.getDouble("admittedPremium"));order.setSource(BatmanRules.PREFIX+basket.getId()+":"+i);
                    order.setStrategyDecision("Reviewed monthly Simulator MARKET continuation; entry guards waived; latest or recorded observed premium; basket credit TP/SL");
                    var placed=execution.executeTriggeredTrade(order);if(first==null)first=placed;
                    var actual=members(basket);if(placed==null||actual.size()!=i+1||!matches(basket,actual,false))throw new IllegalStateException("Market basket confirmation is unresolved");
                }
                arm(basket,members(basket));
                return StrategyApplyResponse.builder().templateId(BatmanRules.TEMPLATE).symbol(basket.getSymbol()).direction("CE+PE").status("triggered").message("Four confirmed monthly Simulator market legs; actual center-credit TP and equal SL armed").tradeRequest(first).build();
            }catch(RuntimeException failure){abort(basket,"Market continuation failed; standard cancellation/exit recovery active");log.warn("Reviewed Batman continuation failed: {}",failure.getMessage());
                return StrategyApplyResponse.builder().templateId(BatmanRules.TEMPLATE).symbol(basket.getSymbol()).status("error").message("Market continuation failed; recovery active").tradeRequest(first).build();}
        });
    }
    static boolean entryWindow(StrategyApplyRequest request,LocalDateTime now){
        if(request.getBatmanManualEntryDay()==null)return BatmanRules.entryWindow(now);
        return request.getBatmanManualEntryDay().equals(now.toLocalDate())
                &&!now.toLocalTime().isBefore(LocalTime.of(9,20))&&now.toLocalTime().isBefore(LocalTime.of(15,0));
    }
    public static boolean matches(BatmanBasket basket,List<TriggeredTradeSetupEntity> actual,boolean complete){
        if(complete&&actual.size()!=4||actual.isEmpty()||actual.size()>4)return false;var plan=new JSONArray(basket.getPlanJson());Set<Integer> seen=new HashSet<>();
        for(var t:actual){
            if(!Objects.equals(t.getAppUserId(),basket.getAppUserId())||!Objects.equals(t.getBrokerCredentialsId(),basket.getBrokerCredentialsId())||!Objects.equals(t.getStrategyRunId(),basket.getStrategyRunId())||!Objects.equals(t.getSymbol(),basket.getSymbol())||t.getStatus()!=TriggeredTradeStatus.EXECUTED||Boolean.TRUE.equals(t.getIntraday()))return false;
            int index=-1;for(int i=0;i<plan.length();i++)if((BatmanRules.PREFIX+basket.getId()+":"+i).equals(t.getSource()))index=i;
            if(index<0||!seen.add(index))return false;var expected=plan.getJSONObject(index);
            if(!Objects.equals(t.getScripCode(),expected.getInt("code"))||!Objects.equals(t.getExchange(),expected.getString("exchange"))||!Objects.equals(t.getOptionType(),expected.getString("side"))||!Objects.equals(t.getQuantity(),expected.getLong("quantity"))||t.getStrikePrice()==null||Double.compare(t.getStrikePrice(),expected.getDouble("strike"))!=0||!TradeSide.entry(t).equals(expected.getString("action"))||!basket.getExpiry().format(DateTimeFormatter.ofPattern("dd/MM/uuuu")).equals(t.getExpiry())||t.getActualEntryPrice()==null||!Double.isFinite(t.getActualEntryPrice())||t.getActualEntryPrice()<=0)return false;
        }
        // A partial prefix is permitted only while the synchronous entry loop is making progress.
        for(int i=0;i<actual.size();i++)if(!seen.contains(i))return false;
        return true;
    }
    private List<TriggeredTradeSetupEntity> members(BatmanBasket basket){return trades.findByStrategyRunId(basket.getStrategyRunId());}
    private void arm(BatmanBasket basket,List<TriggeredTradeSetupEntity> actual){if(!matches(basket,actual,true))throw new IllegalStateException("Four exact confirmed legs required");store.arm(basket,BatmanRules.credit(actual));}
    private void abort(BatmanBasket basket,String message){baskets.outcome(basket.getId(),"EXITING",message);coordinator.requestClose(basket.getStrategyRunId(),"BATMAN_ENTRY_ABORT");}
    @Scheduled(fixedDelayString="${app.strategy.batman-monitor-ms:1000}")
    public synchronized void monitor(){
        var now=LocalDateTime.now(clock);if(!calendar.isTradingDay(now.toLocalDate())||now.toLocalTime().isBefore(LocalTime.of(9,15))||now.toLocalTime().isAfter(LocalTime.of(15,30)))return;
        for(var basket:baskets.findByStateIn(List.of("ENTERING","ARMED","EXITING")))try{
            var run=runs.findById(basket.getStrategyRunId()).orElseThrow();
            if("CLOSED".equals(run.getStatus())){baskets.outcome(basket.getId(),"CLOSED","All basket positions confirmed closed");continue;}
            if(!"OPEN".equals(run.getStatus())){baskets.outcome(basket.getId(),"EXITING","Basket exit is latched; normal order recovery remains active");continue;}
            var actual=members(basket);
            if("ENTERING".equals(basket.getState())){
                if(matches(basket,actual,true)) {
                    try { arm(basket,actual); }
                    catch(IllegalArgumentException invalidCredit) { abort(basket,"Nonpositive or invalid confirmed center credit; unwind required"); }
                }
                else if(!now.isBefore(basket.getClaimedAt().plusSeconds(60)))abort(basket,"Restart/entry recovery: incomplete or unconfirmed basket");
            }else if("ARMED".equals(basket.getState())&&!matches(basket,actual,true))abort(basket,"Basket membership changed; closing remaining exposure");
            else if("EXITING".equals(basket.getState()))coordinator.requestClose(basket.getStrategyRunId(),"BATMAN_ENTRY_ABORT");
            if(!now.isBefore(basket.getExpiry().atTime(15,20)))coordinator.requestClose(basket.getStrategyRunId(),"BATMAN_EXPIRY_EXIT");
        }catch(RuntimeException e){log.warn("Batman basket {} requires recovery: {}",basket.getId(),e.getMessage());}
    }
    private static Set<LocalDate> dates(List<String> values){Set<LocalDate> result=new HashSet<>();for(String s:values)try{result.add(LocalDate.parse(s,DateTimeFormatter.ofPattern("dd/MM/uuuu")));}catch(RuntimeException ignored){}return result;}
    private static String id(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
