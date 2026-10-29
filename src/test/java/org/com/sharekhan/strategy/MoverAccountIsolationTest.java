package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoverAccountIsolationTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void blockedBrokerForOneAccountCannotHoldTheOtherAccountStrategyLock(boolean stock) throws Exception {
        var f = new Fixture(stock);
        var liveStarted = new CountDownLatch(1); var releaseLive = new CountDownLatch(1); var simFinished = new CountDownLatch(1);
        when(f.support.executeTriggeredTrade(any())).thenAnswer(c -> {
            org.com.sharekhan.dto.TriggerRequest t = c.getArgument(0);
            if (t.getUserId()==1L) { liveStarted.countDown(); assertThat(releaseLive.await(3,TimeUnit.SECONDS)).isTrue(); }
            else simFinished.countDown();
            return TriggerTradeRequestEntity.builder().id(t.getUserId()).build();
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var live = threads.submit(() -> f.strategy.apply(f.request(1L)));
            assertThat(liveStarted.await(3,TimeUnit.SECONDS)).isTrue();
            var sim = threads.submit(() -> f.strategy.apply(f.request(3L)));
            assertThat(simFinished.await(2,TimeUnit.SECONDS)).isTrue(); // fails against the old synchronized stock apply
            assertThat(sim.get(2,TimeUnit.SECONDS).getStatus()).isEqualTo("triggered");
            releaseLive.countDown(); assertThat(live.get(2,TimeUnit.SECONDS).getStatus()).isEqualTo("triggered");
            f.strategy.apply(f.request(1L)); f.strategy.apply(f.request(3L));
            verify(f.support,times(2)).executeTriggeredTrade(any());
        } finally { releaseLive.countDown(); threads.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentCallsForSameAccountCannotDoubleSubmit(boolean stock) throws Exception {
        var f = new Fixture(stock); var firstStarted = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(f.support.executeTriggeredTrade(any())).thenAnswer(c -> {
            firstStarted.countDown(); assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();
            return TriggerTradeRequestEntity.builder().id(1L).build();
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> f.strategy.apply(f.request(1L)));
            assertThat(firstStarted.await(3,TimeUnit.SECONDS)).isTrue();
            var second = threads.submit(() -> f.strategy.apply(f.request(1L)));
            release.countDown(); first.get(2,TimeUnit.SECONDS); second.get(2,TimeUnit.SECONDS);
            verify(f.support,times(1)).executeTriggeredTrade(any());
        } finally { release.countDown(); threads.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void expiryAfterQualificationCannotPlaceAnOrder(boolean stock) {
        var f = new Fixture(stock);
        when(f.qualification.isCycleExpired()).thenReturn(true);
        assertThat(f.strategy.apply(f.request(1L)).getMessage()).contains("expired");
        verify(f.support,never()).executeTriggeredTrade(any());
    }

    static class Fixture {
        final StrategySupport support=mock(StrategySupport.class);
        final Fno925EntryQualificationService qualification=mock(Fno925EntryQualificationService.class);
        final StrategyEvaluator strategy;
        final boolean stock;
        Fixture(boolean stock) {
            this.stock=stock;
            var now=LocalDateTime.of(2026,10,21,10,36);
            var clock=Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE);
            var snapshots=mock(MoverSelectionSnapshotStore.class);
            var spot=ScriptMasterEntity.builder().exchange("NC").scripCode(3787).tradingSymbol("WIPRO").build();
            when(snapshots.loadOrCapture(anyString(),any(),any())).thenReturn(Optional.of(List.of(new Fno925Candidate("WIPRO",spot,"CE"))));
            var candle=new StrategyCandle(now.toLocalDate(),LocalTime.of(10,30),189,190,188,189.5,1000L);
            var signal=new Fno925EntryQualificationService.Signal(189.5,188.64,0.46,189,188,candle,"MORNING_ORB");
            if (stock) when(qualification.qualifyWithDirectionalCandle(any(),any())).thenReturn(Fno925EntryQualificationService.Qualification.qualified(signal));
            else when(qualification.qualifyMoverWithFreshRetest(any(),any())).thenReturn(Fno925EntryQualificationService.Qualification.qualified(signal));
            when(support.roundPrice(anyDouble())).thenAnswer(c -> c.getArgument(0));
            when(support.preferredFnoExpiry(anyString(),anyString())).thenReturn("27/10/2026");
            when(support.resolveFnoEntryContract(any(),any(),any(),any(),anyDouble())).thenReturn(new StrategySupport.FnoOptionContract("27/10/2026",190));
            when(support.waiting(any(),any(),anyString())).thenAnswer(c -> StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
            strategy=stock?new Stock0925MoverAtrBreakoutStrategy(support,mock(ScriptMasterRepository.class),mock(MStockGainerLoserService.class),qualification,snapshots,clock)
                    :new Fno0925MoverAtrBreakoutStrategy(support,mock(ScriptMasterRepository.class),mock(MStockGainerLoserService.class),qualification,snapshots,clock);
        }
        StrategyApplyRequest request(Long user) {
            var r=new StrategyApplyRequest();r.setUserId(user);r.setBrokerCredentialsId(user);r.setLots(3);
            r.setInvestmentPerStock(new BigDecimal("500000")); return r;
        }
    }
}
