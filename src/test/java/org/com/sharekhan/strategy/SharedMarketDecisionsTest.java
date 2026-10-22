package org.com.sharekhan.strategy;
import org.com.sharekhan.dto.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class SharedMarketDecisionsTest {
    final LocalDateTime now=LocalDateTime.of(2026,10,22,13,30,5);
    final Clock clock=Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE);
    @Test void matchingConcurrentSubscribersCalculateOnceAndHaveTheSameSignalId() throws Exception {
        var shared=new SharedMarketDecisions(clock); var count=new AtomicInteger(); var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<StrategyApplyResponse>> results=new ArrayList<>();
            for(int i=0;i<20;i++) results.add(pool.submit(() -> shared.evaluate(() -> {
                int decision=shared.share("STRATEGY","SENSEX","ENTRY_RULES",now,count::incrementAndGet);
                return StrategyApplyResponse.builder().message("strike="+decision).build();
            })));
            Set<List<String>> ids=new HashSet<>();
            for(var result:results){var response=result.get(5,TimeUnit.SECONDS);ids.add(response.getSharedSignalIds());assertThat(response.getMessage()).isEqualTo("strike=1");}
            assertThat(count).hasValue(1); assertThat(ids).hasSize(1); assertThat(ids.iterator().next()).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void settingsSymbolsTemplatesAndNewCyclesRemainSeparate() {
        var shared=new SharedMarketDecisions(clock);var count=new AtomicInteger();
        assertThat(shared.share("A","SENSEX","OFFSET=0.25",now,count::incrementAndGet)).isEqualTo(1);
        assertThat(shared.share("A","sensex","OFFSET=0.25",now,count::incrementAndGet)).isEqualTo(1);
        assertThat(shared.share("A","SENSEX","OFFSET=0.35",now,count::incrementAndGet)).isEqualTo(2);
        assertThat(shared.share("A","NIFTY","OFFSET=0.25",now,count::incrementAndGet)).isEqualTo(3);
        assertThat(shared.share("B","SENSEX","OFFSET=0.25",now,count::incrementAndGet)).isEqualTo(4);
    }
    @Test void nestedMarketPreparationDoesNotHoldMapLocksOrMixAccountTrace() {
        var shared=new SharedMarketDecisions(clock);
        var result=shared.evaluate(() -> {
            shared.share("ORB","NIFTY","",now,()-> shared.share("CONTRACT","NIFTY","",now,()->"contract"));
            return StrategyApplyResponse.builder().build();
        });
        assertThat(result.getSharedSignalIds()).hasSize(2);
        assertThat(shared.evaluate(()->StrategyApplyResponse.builder().build()).getSharedSignalIds()).isEmpty();
    }
    @Test void failedCalculationDoesNotPoisonLaterSubscribersAndTraceIsCleared() {
        var shared=new SharedMarketDecisions(clock);
        assertThatThrownBy(()->shared.evaluate(()-> {
            shared.share("A","SENSEX","",now,()->{throw new IllegalStateException("feed unavailable");}); return null;
        })).hasMessageContaining("feed unavailable");
        assertThat(shared.share("A","SENSEX","",now,()->"ready")).isEqualTo("ready");
        assertThat(shared.evaluate(()->StrategyApplyResponse.builder().build()).getSharedSignalIds()).isEmpty();
    }
    @Test void queuedCycleCannotTradeAfterItsAgeOrCompletedCandleBoundary() {
        var shared=new SharedMarketDecisions(clock); var called=new AtomicBoolean();
        assertThatThrownBy(()->shared.withinCycle(now.minusSeconds(31),()->shared.share("A","SENSEX","",now,()->{called.set(true);return 1;})))
                .hasMessageContaining("expired");
        assertThat(called).isFalse();
        assertThatThrownBy(()->shared.withinCycle(now.minusSeconds(10),()->shared.share("A","SENSEX","",now,()->1)))
                .hasMessageContaining("expired");
    }
    @Test void executionDeadlineDoesNotExtendWhenAnotherSubscriberReusesTheSignal() {
        var instant=new AtomicReference<>(now.atZone(StrategySupport.MARKET_ZONE).toInstant());
        var ticking=new Clock(){public java.time.ZoneId getZone(){return StrategySupport.MARKET_ZONE;}
            public Clock withZone(java.time.ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        var shared=new SharedMarketDecisions(ticking);var count=new AtomicInteger();
        shared.share("A","SENSEX","",now,count::incrementAndGet);
        instant.set(now.withSecond(29).atZone(StrategySupport.MARKET_ZONE).toInstant());
        shared.evaluate(()->{
            assertThat(shared.share("A","SENSEX","",now.withSecond(29),count::incrementAndGet)).isOne();
            instant.set(now.withSecond(31).atZone(StrategySupport.MARKET_ZONE).toInstant());
            assertThat(shared.executionFresh()).isFalse();return StrategyApplyResponse.builder().build();
        });
        assertThat(shared.share("A","SENSEX","",now.withSecond(31),count::incrementAndGet)).isEqualTo(2);
    }
    @Test void preparedTriggerIsCopiedAndBoundToEachSubscriberIndependently() {
        var trigger=new TriggerRequest();trigger.setInstrument("SENSEX");trigger.setStrikePrice(73200d);trigger.setEntryPrice(125d);
        var decision=new PreparedStrategyDecision(StrategyApplyResponse.builder().triggerRequest(trigger).build());
        var first=new StrategyApplyRequest();first.setUserId(1L);first.setBrokerCredentialsId(10L);first.setLots(1);
        var second=new StrategyApplyRequest();second.setUserId(2L);second.setBrokerCredentialsId(20L);second.setLots(3);
        var a=decision.forSubscriber(first).getTriggerRequest();var b=decision.forSubscriber(second).getTriggerRequest();
        assertThat(a.getUserId()).isEqualTo(1);assertThat(b.getUserId()).isEqualTo(2);
        assertThat(a.getQuantity()).isEqualTo(1);assertThat(b.getQuantity()).isEqualTo(3);
        assertThat(a.getBrokerCredentialsId()).isEqualTo(10);assertThat(b.getBrokerCredentialsId()).isEqualTo(20);
        a.setStrikePrice(999d);assertThat(decision.forSubscriber(second).getTriggerRequest().getStrikePrice()).isEqualTo(73200);
        assertThat(b.getStrategyRunId()).isNull();
    }
}
