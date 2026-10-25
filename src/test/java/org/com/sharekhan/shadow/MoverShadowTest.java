package org.com.sharekhan.shadow;

import com.fasterxml.jackson.databind.*;
import org.com.sharekhan.cache.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.ws.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.com.sharekhan.shadow.ShadowModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoverShadowTest {
    final Instant at=Instant.parse("2026-10-23T05:21:06Z");
    final LocalDate day=LocalDate.of(2026,10,23);
    final QuoteCacheService quotes=new QuoteCacheService();
    final LtpCacheService prices=new LtpCacheService();
    final NseMarketCalendar calendar=mock(NseMarketCalendar.class);
    final WebSocketSubscriptionService feeds=mock(WebSocketSubscriptionService.class);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JdbcDataSource ds; MoverShadowStore store; MoverShadowRecorder recorder;
    @BeforeEach void setup(){
        ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/mover-shadow-schema.sql")).execute(ds);
        store=new MoverShadowStore(ds,mapper);recorder=new MoverShadowRecorder(store,quotes,prices,calendar,feeds);
        when(calendar.isTradingDay(any())).thenReturn(true);
    }
    @AfterEach void stop(){recorder.stop();}
    Book book(Instant t,double bid,double ask,double ltp){return new Book(bid,ask,ltp,t,t,"SHOONYA","NF","token:contract",null,null,true);}
    Sample sample(Instant t,double spot,double bid,double ask,double ltp,Double minuteClose){return new Sample(t,book(t,bid,ask,ltp),spot,t,t.atZone(MoverShadowRecorder.ZONE).toLocalDateTime().minusMinutes(1).withSecond(0).withNano(0),minuteClose);}
    Decision decision(int lots,Sample s){return new Decision(UUID.randomUUID().toString(),day,at,3L,4L,101L,"setup","NF:42:expiry:300:PE","ABC","NF",42,43,"27/10/2026",300d,"PE",lots,1875L*lots,295.6,297.24,List.of(293.42,292.33,291.24),1.5,3.5,5000,20,true,true,s,LocalDateTime.of(2026,10,23,10,45),"V1",List.of("shared"));}
    Event event(Decision d){return new Event(d.id(),day,d.contractKey(),"DECISION",d.userId(),d.at(),store.json(d));}
    @Test void schemaIsRepeatableAndDecisionsRoundTripWithoutTouchingTradeTables(){
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/mover-shadow-schema.sql")).execute(ds);
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));store.append(List.of(event(d)));
        assertThat(store.decisions(day,3L)).containsExactly(d);assertThat(store.decisions(day,4L)).isEmpty();
        assertThat(store.dailyUsage(day)[0]).isOne();assertThat(store.bytes()).isPositive();
        assertThat(new JdbcTemplate(ds).queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",String.class)).allMatch(n->n.startsWith("MOVER_SHADOW_"));
    }
    @Test void twoSpreadPoliciesRetainSignalSlippageAndHardLimit(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        assertThat(MoverShadowReport.guards(d,MoverShadowReport.Policy.CURRENT)).contains("HARD_SPREAD_LIMIT","SOFT_SPREAD_LIMIT");
        assertThat(MoverShadowReport.guards(d,MoverShadowReport.Policy.IGNORE_SOFT_SPREAD)).containsExactly("HARD_SPREAD_LIMIT");
        assertThat(MoverShadowReport.guards(d,MoverShadowReport.Policy.IGNORE_BOTH_SPREADS)).isEmpty();
        var invalid=decision(1,sample(at,296,6.6,21,6.7,295.5));
        assertThat(MoverShadowReport.guards(invalid,MoverShadowReport.Policy.IGNORE_BOTH_SPREADS)).contains("SPOT_TRIGGER_INVALID","SLIPPAGE_LIMIT");
    }
    @Test void staleCrossedOrWrongProviderBooksCannotCreateShadowFill(){
        assertThat(MoverShadowReport.bookUsable(book(at.minusSeconds(6),6,7,6.5),at,5000,"NF")).isFalse();
        assertThat(MoverShadowReport.bookUsable(book(at,7,6,6.5),at,5000,"NF")).isFalse();
        assertThat(MoverShadowReport.bookUsable(book(at,6,7,6.5),at,5000,"BF")).isFalse();
        assertThat(MoverShadowReport.bookUsable(book(at.plusSeconds(1),6,7,6.5),at,5000,"NF")).isFalse();
    }
    @Test void oneLotT1ExitsAllAndChargesAreSubtracted(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        var result=MoverShadowReport.replay(d,List.of(sample(at.plusSeconds(5),293.4,7.9,8.15,7.9,295d)));
        assertThat(result.get("status")).isEqualTo("ESTIMATED_QUOTE_ONLY");assertThat(result.get("remainingLots")).isEqualTo(0);
        assertThat(result.get("gross")).isEqualTo(1875d);assertThat(result.get("net")).isEqualTo(1836.5);
    }
    @Test void threeLotsBookOneAtT1AndRemainingStopUsesPremiumNotSpotEntry(){
        var d=decision(3,sample(at,295.3,6.6,6.9,6.7,295.5));
        var result=MoverShadowReport.replay(d,List.of(sample(at.plusSeconds(5),293.4,7.9,8.15,7.9,295d),sample(at.plusSeconds(10),294,6.85,7,6.9,294d)));
        assertThat(result.get("remainingLots")).isEqualTo(0);
        var exits=(List<Map<String,Object>>)result.get("exits");assertThat(exits).hasSize(2);
        assertThat(exits.get(0)).containsEntry("lots",1).containsEntry("reason","TARGET_1");assertThat(exits.get(1)).containsEntry("lots",2).containsEntry("reason","STOP");
    }
    @Test void stopRequiresCompletedMinuteAndCurrentSpotConfirmation(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        var result=MoverShadowReport.replay(d,List.of(sample(at.plusSeconds(5),298,6.1,6.3,6.2,296d),sample(at.plusSeconds(10),296,6.1,6.3,6.2,298d)));
        assertThat(result.get("remainingLots")).isEqualTo(1);
        assertThat(result.get("net")).isNull();
    }
    @Test void missingTicksAndInsufficientExitDepthDoNotBecomeCertainProfits(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        assertThat(MoverShadowReport.replay(d,List.of(sample(at.plusSeconds(20),293.4,7.9,8.15,7.9,295d))).get("status")).isEqualTo("AMBIGUOUS_GAPS");
        var empty=MoverShadowReport.replay(d,List.of());assertThat(empty.get("net")).isNull();
        var crossed=sample(at.plusSeconds(5),293.4,9,8,8,295d);assertThat(MoverShadowReport.replay(d,List.of(crossed)).get("remainingLots")).isEqualTo(1);
    }
    @Test void changedStrikeAndRetryAreNotExtraEntriesAndAccountsRemainSeparate(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));var retry=decision(1,d.sample());
        store.append(List.of(event(d),event(retry),new Event(UUID.randomUUID().toString(),day,d.contractKey(),"SAMPLE",null,at.plusSeconds(5),store.json(sample(at.plusSeconds(5),293.4,7.9,8.15,7.9,295d)))));
        var reports=new MoverShadowReport(store,recorder,calendar);
        var r=reports.build(day,3L);var policies=(List<Map<String,Object>>)r.get("policies");
        assertThat(policies.get(2).get("entries")).isEqualTo(1);assertThat(r.get("attemptCount")).isEqualTo(2);
        assertThat(reports.build(day,4L).get("attemptCount")).isEqualTo(0);
    }
    @Test void writerQueuesAreBoundedAndFailedObserverHasNoOrderDependency(){
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        for(int i=0;i<1024;i++)assertThat(recorder.offer(d)).isTrue();assertThat(recorder.offer(d)).isFalse();
        assertThat(recorder.health()).containsEntry("dropped",1L).containsEntry("queueSize",1024);
        assertThat(Arrays.stream(MoverShadowRecorder.class.getDeclaredFields()).map(f->f.getType().getName())).noneMatch(n->n.contains("BrokerService")||n.contains("TradeExecutionService")||n.contains("TriggeredTradeSetupRepository"));
    }
    @Test void quotaStopsOnlyObserverAndRestartRestoresPersistedWatches() throws Exception {
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));recorder.offer(d);recorder.tick(at);
        assertThat(store.events(day,"WATCH")).hasSize(1);
        var restarted=new MoverShadowRecorder(store,quotes,prices,calendar,feeds);
        restarted.tick(at.plusSeconds(5));verify(feeds,atLeastOnce()).retainObserverFeed("mover-shadow-option:"+d.contractKey(),"NF42",true);
        ReflectionTestUtils.setField(restarted,"maxRows",1L);restarted.tick(at.plusSeconds(10));assertThat(restarted.health().get("state")).isEqualTo("QUOTA_STOP");restarted.stop();
    }
    @Test void recorderNeverRefreshesBookAgeAndRejectsMixedProviderPremium(){
        quotes.recordQuote(42,6.6,6.9,6.7,"SHOONYA","NF","verified",null,null);
        var bookAt=quotes.getSnapshot(42).orElseThrow().getLastBookAt();
        quotes.recordQuote(42,null,null,123d,"OTHER",null,null,null,null);
        assertThat(quotes.getSnapshot(42).orElseThrow().getLastBookAt()).isEqualTo(bookAt);
        assertThat(recorder.snapshot(42,43,at).book().ltp()).isNull();
        quotes.recordQuote(42,6.7,null,null,"SHOONYA","NF","verified",null,null);
        assertThat(quotes.getSnapshot(42).orElseThrow().isCoherentBook()).isFalse();
    }
    @Test void summaryHistoryKeepsChronologicalHoldoutAndRetentionDoesNotInventRawData() {
        var reports=new MoverShadowReport(store,recorder,calendar);
        for(int i=0;i<20;i++) {
            var date=day.minusDays(20-i);
            store.saveReport(date,3L,store.json(Map.of("day",date,"uniqueSetups",3,"policies",List.of())));
        }
        assertThat(reports.history(3L)).containsEntry("sessions",20).containsEntry("uniqueSetups",60L).containsEntry("minimumReviewSampleReached",true);
        assertThat((List<?>)reports.history(3L).get("earlierSessions")).hasSize(14);
        assertThat((List<?>)reports.history(3L).get("laterHoldoutSessions")).hasSize(6);
        assertThat(reports.history(4L).get("sessions")).isEqualTo(0);
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));store.append(List.of(event(d)));
        store.retain(day.plusDays(1),day.minusDays(90));assertThat(store.decisions(day,3L)).isEmpty();
        assertThat(store.history(3L)).hasSize(20);
    }
    @Test void anotherAccountsWatchKeysAreNotExposedInHealth() {
        store.saveReport(day,0L,store.json(Map.of("state","RECORDING","gaps",Map.of("private-other-contract","STALE"))));
        var reports=new MoverShadowReport(store,recorder,calendar);
        var health=(Map<?,?>)reports.build(day,4L).get("captureHealth");assertThat((Map<?,?>)health.get("gaps")).isEmpty();
    }

    @Test void missingEarlierDecisionsExcludeOtherwiseProfitableModelFromResolvedTotals() {
        var d=decision(1,sample(at,295.3,6.6,6.9,6.7,295.5));
        store.append(List.of(event(d),new Event(UUID.randomUUID().toString(),day,d.contractKey(),"SAMPLE",null,at.plusSeconds(5),store.json(sample(at.plusSeconds(5),293.4,7.9,8.15,7.9,295d)))));
        store.saveReport(day,0L,store.json(Map.of("state","QUOTA_STOP","dropped",1)));
        var result=new MoverShadowReport(store,recorder,calendar).build(day,3L);
        var policies=(List<Map<String,Object>>)result.get("policies");assertThat(policies.get(2).get("resolvedQuoteModels")).isEqualTo(0);assertThat(policies.get(2).get("quoteModelNet")).isNull();
    }

    @Test void signalContextClearsEvenOnFailure(){
        assertThatThrownBy(()->MoverShadowSignalContext.withSignal(LocalDateTime.now(),()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(MoverShadowSignalContext.current()).isNull();
    }
}
