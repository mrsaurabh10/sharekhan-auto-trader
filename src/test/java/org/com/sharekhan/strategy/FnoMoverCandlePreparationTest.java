package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FnoMoverCandlePreparationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026,11,16,10,0);
    private Fno925Candidate candidate(int i) {
        return new Fno925Candidate("S"+i, ScriptMasterEntity.builder().exchange("NC").scripCode(i).tradingSymbol("S"+i).build(), "CE");
    }
    private CandleLoad data(LocalDateTime now) {
        List<StrategyCandle> bars = new ArrayList<>();
        for (int i=0;i<75;i++) bars.add(new StrategyCandle(now.toLocalDate().minusDays(3), LocalTime.of(9,15).plusMinutes(5L*i),100,102,99,101,1000L));
        var at=FnoMoverCandlePreparation.expected(now);
        bars.add(new StrategyCandle(at.toLocalDate(),at.toLocalTime(),100,102,99,101,1000L));
        return new CandleLoad(bars,true,null);
    }

    @Test
    void blockedFirstProviderCannotBlockOtherFourOrAccountThreadsAndAccountsShareLoads() throws Exception {
        var support=mock(StrategySupport.class);var pool=Executors.newFixedThreadPool(5);
        var clock=Clock.fixed(NOW.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE);
        CountDownLatch firstStarted=new CountDownLatch(1),release=new CountDownLatch(1),others=new CountDownLatch(4);
        when(support.loadCompletedIndicatorCandles(any(),eq(76),eq(NOW))).thenAnswer(a->{
            ScriptMasterEntity spot=a.getArgument(0);
            if(spot.getScripCode()==1){firstStarted.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();}
            else others.countDown();
            return data(NOW);
        });
        var preparation=new FnoMoverCandlePreparation(support,clock,pool);
        var candidates=List.of(candidate(1),candidate(2),candidate(3),candidate(4),candidate(5));
        try {
            preparation.prepare(candidates,NOW);
            assertThat(firstStarted.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(others.await(2,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(candidates.subList(1,5).stream().anyMatch(c->preparation.ready(c,NOW).isEmpty())) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(preparation.ready(candidates.get(0),NOW)).isEmpty();
            preparation.prepare(candidates,NOW); // Second account must reuse all pending/completed jobs.
            pool.shutdown();release.countDown();assertThat(pool.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
            for(var c:candidates) assertThat(preparation.ready(c,NOW)).isPresent();
            verify(support,times(5)).loadCompletedIndicatorCandles(any(),eq(76),eq(NOW));
        } finally {release.countDown();preparation.close();}
    }

    @Test
    void oldQueuedJobAndOldCompletedBarsCannotBeReusedAtNextSignalBoundary() {
        var support=mock(StrategySupport.class);var pool=new QueuedExecutor();var clock=new MutableClock(NOW);
        var preparation=new FnoMoverCandlePreparation(support,clock,pool);var candidate=candidate(1);
        when(support.loadCompletedIndicatorCandles(any(),eq(76),any())).thenAnswer(a->data(a.getArgument(2)));
        preparation.prepare(List.of(candidate),NOW);
        clock.at.set(NOW.plusMinutes(5));
        preparation.prepare(List.of(candidate),NOW.plusMinutes(5));
        assertThat(pool.jobs).hasSize(1); // No overlapping work for one stock.
        pool.runNext();verifyNoInteractions(support); // Expired before start, so no provider read.
        assertThat(preparation.ready(candidate,NOW.plusMinutes(5))).isEmpty();
        preparation.prepare(List.of(candidate),NOW.plusMinutes(5));pool.runNext();
        assertThat(preparation.ready(candidate,NOW.plusMinutes(5))).isPresent();
        clock.at.set(NOW.plusMinutes(10));assertThat(preparation.ready(candidate,NOW.plusMinutes(10))).isEmpty();
    }

    @Test
    void insufficientCompletedHistoryWaitsAndRetriesWithoutUsingStaleBars() {
        var support=mock(StrategySupport.class);var pool=new QueuedExecutor();var clock=new MutableClock(NOW);
        var preparation=new FnoMoverCandlePreparation(support,clock,pool);var candidate=candidate(1);
        when(support.loadCompletedIndicatorCandles(any(),eq(76),any())).thenReturn(new CandleLoad(data(NOW).candles().subList(1,76),true,null));
        preparation.prepare(List.of(candidate),NOW);pool.runNext();
        assertThat(preparation.ready(candidate,NOW)).isEmpty();
        preparation.prepare(List.of(candidate),NOW);assertThat(pool.jobs).isEmpty();
        clock.at.set(NOW.plusSeconds(6));preparation.prepare(List.of(candidate),NOW.plusSeconds(6));
        assertThat(pool.jobs).hasSize(1);
    }

    @Test
    void saturatedExecutorNeverFallsBackToProviderWorkOnAccountThread() {
        var support=mock(StrategySupport.class);var pool=new QueuedExecutor();pool.shutdown();
        var clock=new MutableClock(NOW);var preparation=new FnoMoverCandlePreparation(support,clock,pool);
        preparation.prepare(List.of(candidate(1)),NOW);
        assertThat(preparation.ready(candidate(1),NOW)).isEmpty();verifyNoInteractions(support);
    }

    private static class QueuedExecutor extends AbstractExecutorService {
        final Queue<Runnable> jobs=new ArrayDeque<>();boolean stopped;
        public void execute(Runnable r){if(stopped)throw new RejectedExecutionException();jobs.add(r);}
        void runNext(){jobs.remove().run();}
        public void shutdown(){stopped=true;}public List<Runnable> shutdownNow(){stopped=true;return List.of();}
        public boolean isShutdown(){return stopped;}public boolean isTerminated(){return stopped;}
        public boolean awaitTermination(long t,TimeUnit u){return stopped;}
    }
    private static class MutableClock extends Clock {
        final AtomicReference<LocalDateTime> at;
        MutableClock(LocalDateTime now){at=new AtomicReference<>(now);}
        public ZoneId getZone(){return StrategySupport.MARKET_ZONE;}public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return at.get().atZone(getZone()).toInstant();}
    }
}
