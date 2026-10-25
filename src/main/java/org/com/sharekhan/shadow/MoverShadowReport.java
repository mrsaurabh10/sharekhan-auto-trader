package org.com.sharekhan.shadow;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.service.TradeCostCalculator;
import org.com.sharekhan.service.NseMarketCalendar;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.com.sharekhan.shadow.ShadowModels.*;

@Service
public class MoverShadowReport {
    public enum Policy { CURRENT, IGNORE_SOFT_SPREAD, IGNORE_BOTH_SPREADS }
    private final MoverShadowStore store;
    private final MoverShadowRecorder recorder;
    private final NseMarketCalendar calendar;
    private final Semaphore reports=new Semaphore(1);
    public MoverShadowReport(MoverShadowStore store,MoverShadowRecorder recorder,NseMarketCalendar calendar) {this.store=store;this.recorder=recorder;this.calendar=calendar;}
    public Map<String,Object> report(LocalDate day,Long user) {
        if(day.isBefore(LocalDate.now(MoverShadowRecorder.ZONE).minusDays(90)) || day.isAfter(LocalDate.now(MoverShadowRecorder.ZONE)))throw new IllegalArgumentException("Choose a date in the last 90 days");
        if(!reports.tryAcquire())throw new BusyException();
        try {
            var saved=store.archived(day,user);
            if(saved.isPresent()&&store.decisions(day,user).isEmpty())return store.read(saved.get(),Map.class);
            return build(day,user);
        } finally {reports.release();}
    }
    public Map<String,Object> build(LocalDate day,Long user) {
        List<Decision> decisions=store.decisions(day,user);
        Set<String> keys=new HashSet<>();decisions.forEach(d->keys.add(d.contractKey()));
        Map<String,List<Sample>> samples=new HashMap<>();
        for(var e:store.events(day,"SAMPLE"))if(keys.contains(e.key()))samples.computeIfAbsent(e.key(),ignored->new ArrayList<>()).add(store.read(e.payload(),Sample.class));
        Map<String,Object> captureHealth=store.archived(day,0L).map(s->store.read(s,Map.class)).orElse(Map.of("state","NO_CAPTURE_RECORDED"));
        boolean captureIncomplete=((Number)captureHealth.getOrDefault("dropped",0)).longValue()>0
                ||((Number)captureHealth.getOrDefault("writerFailures",0)).longValue()>0;
        List<Map<String,Object>> policyReports=new ArrayList<>();
        for(var policy:Policy.values()) {
            List<Map<String,Object>> trades=new ArrayList<>();Set<String> entered=new HashSet<>();
            for(var d:decisions) {
                // One entry per account/broker/symbol/day; retries and strike changes are alternatives.
                String run=d.userId()+":"+d.brokerId()+":"+d.symbol();
                if(entered.contains(run) || !guards(d,policy).isEmpty())continue;
                entered.add(run);var modeled=replay(d,samples.getOrDefault(d.contractKey(),List.of()));
                if(captureIncomplete)modeled.put("status","AMBIGUOUS_CAPTURE_GAPS");trades.add(modeled);
            }
            List<Map<String,Object>> closed=trades.stream().filter(t->"ESTIMATED_QUOTE_ONLY".equals(t.get("status"))).sorted(Comparator.comparing(t->t.get("exitAt").toString())).toList();
            double net=0,peak=0,drawdown=0;long wins=0,losses=0;
            for(var t:closed){double n=((Number)t.get("net")).doubleValue();net+=n;peak=Math.max(peak,net);drawdown=Math.max(drawdown,peak-net);if(n>0)wins++;if(n<0)losses++;}
            var result=new LinkedHashMap<String,Object>();result.put("policy",policy);result.put("entries",trades.size());result.put("resolvedQuoteModels",closed.size());
            result.put("unresolvedOrAmbiguous",trades.size()-closed.size());result.put("wins",wins);result.put("losses",losses);
            result.put("quoteModelNet",closed.isEmpty()?null:money(net));result.put("quoteModelMaxDrawdown",closed.isEmpty()?null:money(drawdown));result.put("trades",trades);policyReports.add(result);
        }
        List<Map<String,Object>> attempts=new ArrayList<>();
        for(var d:decisions){var a=new LinkedHashMap<String,Object>();a.put("decision",d);a.put("blockedBy",guards(d,Policy.CURRENT));
            var b=d.sample().book();a.put("spreadPercent",spread(b));a.put("spreadCostRupees",b!=null&&price(b.ask())&&price(b.bid())?money((b.ask()-b.bid())*d.quantity()):null);
            a.put("depthStatus",b==null||b.askQuantity()==null||b.bidQuantity()==null?"UNKNOWN":"TOP_LEVEL_ONLY");attempts.add(a);}
        var out=new LinkedHashMap<String,Object>();out.put("schemaVersion",1);out.put("day",day);out.put("userId",user);out.put("attemptCount",decisions.size());
        out.put("uniqueSetups",decisions.stream().map(Decision::setupKey).distinct().count());out.put("policies",policyReports);out.put("attempts",attempts);
        out.put("captureHealth",scopedHealth(captureHealth,keys));
        out.put("currentObserver",scopedHealth(recorder.health(),keys));out.put("interpretation","Sampled ask-entry/bid-exit quote models, not actual or guaranteed fills. Unknown depth, missing ticks, provider gaps and after-close exits are explicit. No historical backfill. Review at least 20 sessions and 50 unique setups before considering policy changes.");return out;
    }
    private static Map<String,Object> scopedHealth(Map<String,Object> health,Set<String> keys) {
        var scoped=new LinkedHashMap<String,Object>(health);
        var gaps=new LinkedHashMap<String,Object>();
        Object source=health.get("gaps");
        if(source instanceof Map<?,?> all)all.forEach((key,value)->{if(keys.contains(String.valueOf(key)))gaps.put(String.valueOf(key),value);});
        scoped.put("gaps",gaps);return scoped;
    }
    static Double spread(Book b){return b!=null&&price(b.bid())&&price(b.ask())&&b.ask()>=b.bid()?100*(b.ask()-b.bid())/((b.bid()+b.ask())/2):null;}
    static List<String> guards(Decision d,Policy policy) {
        List<String> reasons=new ArrayList<>();Sample s=d.sample();Book b=s==null?null:s.book();
        if(!d.signalFresh())reasons.add("EXPIRED_SIGNAL");if(!d.entryWindowOpen())reasons.add("ENTRY_WINDOW_CLOSED");
        if(d.quantity()<=0||d.lots()<=0||d.quantity()%d.lots()!=0)reasons.add("INVALID_SIZE");
        if(s==null||!price(s.spot())||s.spotAt()==null||s.spotAt().isAfter(d.at())||s.spotAt().plusMillis((long)d.maxAgeMs()).isBefore(d.at()))reasons.add("SPOT_UNVERIFIED");
        else if("PE".equals(d.side())?s.spot()>d.entry():s.spot()<d.entry())reasons.add("SPOT_TRIGGER_INVALID");
        if(!bookUsable(b,d.at(),d.maxAgeMs(),d.exchange()))reasons.add("BOOK_UNVERIFIED");
        else {
            if(b.ask()>d.priceCeiling()+.000001)reasons.add("SLIPPAGE_LIMIT");
            double sp=spread(b);
            if(policy!=Policy.IGNORE_BOTH_SPREADS&&sp>d.hardSpread())reasons.add("HARD_SPREAD_LIMIT");
            if(policy==Policy.CURRENT&&sp>d.softSpread())reasons.add("SOFT_SPREAD_LIMIT");
            if(b.askQuantity()!=null&&b.askQuantity()<d.quantity())reasons.add("INSUFFICIENT_DISPLAYED_ENTRY_QUANTITY");
        }
        return reasons;
    }
    static boolean bookUsable(Book b,Instant at,double age,String exchange) {
        if(b==null||!b.coherent()||!price(b.bid())||!price(b.ask())||b.ask()<b.bid()||b.bookAt()==null||b.bookAt().isAfter(at)||b.bookAt().plusMillis((long)age).isBefore(at))return false;
        return ("SHOONYA".equals(b.source())&&Objects.equals(exchange,b.exchange())&&b.providerIdentity()!=null&&!b.providerIdentity().isBlank());
    }
    static Map<String,Object> replay(Decision d,List<Sample> path) {
        var out=new LinkedHashMap<String,Object>();out.put("symbol",d.symbol());out.put("contract",d.contractKey());out.put("requestId",d.requestId());out.put("entryAt",d.at());
        double entry=d.sample().book().ask();out.put("entryAsk",entry);out.put("quantity",d.quantity());out.put("assumption","Immediate quoted ask entry; displayed depth does not guarantee a fill");
        int remaining=d.lots(),stage=1;long unit=d.quantity()/remaining;double gross=0,cost=0,optionStop=0,t1Premium=0;boolean gaps=false;
        Instant last=d.at();List<Map<String,Object>> exits=new ArrayList<>();
        for(var s:path) {
            if(!s.at().isAfter(d.at()))continue;
            if(!s.at().atZone(MoverShadowRecorder.ZONE).toLocalDate().equals(d.day()))continue;
            if(!s.at().atZone(MoverShadowRecorder.ZONE).toLocalTime().isBefore(LocalTime.of(15,30)))break;
            if(Duration.between(last,s.at()).toMillis()>15000)gaps=true;
            last=s.at();Book b=s.book();
            if(!bookUsable(b,s.at(),d.maxAgeMs(),d.exchange())||!price(b.ltp())||b.ltpAt()==null||b.ltpAt().isAfter(s.at())||b.ltpAt().plusMillis((long)d.maxAgeMs()).isBefore(s.at())
                ||!price(s.spot())||s.spotAt()==null||s.spotAt().isAfter(s.at())||s.spotAt().plusMillis((long)d.maxAgeMs()).isBefore(s.at())) {gaps=true;continue;}
            boolean pe="PE".equals(d.side());
            boolean minuteValid=s.minuteAt()!=null&&s.minuteClose()!=null
                && !s.minuteAt().isBefore(d.at().atZone(MoverShadowRecorder.ZONE).toLocalDateTime().withSecond(0).withNano(0))
                && !s.minuteAt().plusMinutes(1).isAfter(s.at().atZone(MoverShadowRecorder.ZONE).toLocalDateTime())
                && !s.minuteAt().plusMinutes(2).isBefore(s.at().atZone(MoverShadowRecorder.ZONE).toLocalDateTime());
            boolean stop=optionStop>0?b.ltp()<=optionStop:minuteValid&&(pe?s.minuteClose()>=d.stop()&&s.spot()>=d.stop():s.minuteClose()<=d.stop()&&s.spot()<=d.stop());
            Double target=stage<=d.targets().size()?d.targets().get(stage-1):null;
            boolean hit=target!=null&&target>0&&(pe?s.spot()<=target:s.spot()>=target);
            int bookLots=stop?remaining:hit?lotsAtStage(d.lots(),remaining,stage):0;
            if(bookLots<=0)continue;
            var trade=TriggeredTradeSetupEntity.builder().actualEntryPrice(entry).quantity(unit*bookLots).lots(bookLots).exitPrice(b.bid()).pnl((b.bid()-entry)*unit*bookLots).build();
            var charges=TradeCostCalculator.calculateCharges(trade);
            if(!stop) {
                // Production target exits also require a net-profitable premium. Use the bid conservatively.
                Double floor=TradeCostCalculator.minimumProfitableExitPrice(trade,.05);
                if(floor==null||b.ltp()+.000001<floor||b.bid()+.000001<floor)continue;
            }
            if(b.bidQuantity()!=null&&b.bidQuantity()<unit*bookLots){gaps=true;continue;}
            gross+=(b.bid()-entry)*unit*bookLots;cost+=charges.totalTradeCost();
            exits.add(Map.of("at",s.at(),"reason",stop?"STOP":"TARGET_"+stage,"lots",bookLots,"bid",b.bid(),"gross",money((b.bid()-entry)*unit*bookLots),"estimatedCharges",charges.totalTradeCost()));
            remaining-=bookLots;
            if(stage==1&&!stop){optionStop=entry;t1Premium=b.ltp();}else if(stage==2&&!stop)optionStop=t1Premium;
            if(!stop)stage++;
            if(remaining==0){out.put("exitAt",s.at());break;}
        }
        out.put("exits",exits);out.put("remainingLots",remaining);out.put("depth", "TOP_LEVEL_OR_UNKNOWN; FILL_NOT_CONFIRMED");out.put("coverageGaps",gaps);
        out.put("status",remaining>0?"INSUFFICIENT_DATA_OR_NO_EXECUTABLE_CLOSING_QUOTE":gaps?"AMBIGUOUS_GAPS":"ESTIMATED_QUOTE_ONLY");
        out.put("gross",money(gross));out.put("estimatedCharges",money(cost));out.put("net",remaining==0?money(gross-cost):null);return out;
    }
    private static int lotsAtStage(int total,int remaining,int stage) {
        if(total<=1)return remaining;
        if(total==2)return stage==1?1:remaining;
        if(total==3)return 1;
        int one=Math.max(1,(int)Math.round(total*.4)),three=Math.max(1,total-one-one),two=Math.max(1,total-one-three);
        return Math.min(remaining,stage==1?one:stage==2?two:three);
    }
    private static double money(double n){return Math.round(n*100)/100d;}
    @Scheduled(cron="0 5 16 * * MON-FRI",zone="Asia/Kolkata")
    public void archiveAndRetain() {
        if(!reports.tryAcquire())return;
        try {
            LocalDate now=LocalDate.now(MoverShadowRecorder.ZONE);
            for(Long user:store.users(now)) {
                var summary=build(now,user);summary.put("attempts",List.of());summary.put("rawDetails","Raw entry details are retained separately for seven trading days");
                store.saveReport(now,user,store.json(summary));
            }
            LocalDate cutoff=now;int days=0;
            while(days<7){cutoff=cutoff.minusDays(1);if(calendar.isTradingDay(cutoff))days++;}
            store.retain(cutoff,now.minusDays(90));
        }catch(Exception e){org.slf4j.LoggerFactory.getLogger(MoverShadowReport.class).warn("Mover shadow archival unavailable: {}",e.getClass().getSimpleName());}
        finally{reports.release();}
    }
    public Map<String,Object> history(Long user) {
        var history=new ArrayList<Map<String,Object>>();long setups=0;
        for(String json:store.history(user)) {
            Map<String,Object> r=store.read(json,Map.class);setups+=((Number)r.getOrDefault("uniqueSetups",0)).longValue();
            history.add(Map.of("day",r.get("day"),"uniqueSetups",r.get("uniqueSetups"),"policies",r.get("policies")));
        }
        int split=(int)Math.floor(history.size()*.7);
        return Map.of("sessions",history.size(),"uniqueSetups",setups,"minimumReviewSampleReached",history.size()>=20&&setups>=50,
                "earlierSessions",history.subList(0,split),"laterHoldoutSessions",history.subList(split,history.size()),
                "interpretation","Chronological 70/30 descriptive split. Policies are fixed, not fitted. Review milestone is not evidence of profitability; inspect unresolved cases and coverage.");
    }
    public static class BusyException extends RuntimeException { }
}
