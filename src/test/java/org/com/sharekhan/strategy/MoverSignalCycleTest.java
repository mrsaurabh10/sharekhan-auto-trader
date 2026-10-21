package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoverSignalCycleTest {
    final StrategySupport support = mock(StrategySupport.class);
    final Fno925EntryQualificationService service = new Fno925EntryQualificationService(support);
    final LocalDateTime now = LocalDateTime.of(2026,10,21,10,36,0);
    final Fno925Candidate candidate = new Fno925Candidate("WIPRO",
            ScriptMasterEntity.builder().scripCode(3787).exchange("NC").build(), "CE");
    MoverSignalCycleTest() {
        clock(now);
        when(support.loadCandlesWithHistoricalFallback(any(),anyInt())).thenReturn(new CandleLoad(List.of(),true,null));
    }
    void clock(LocalDateTime time) {
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(time.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
    }
    Fno925EntryQualificationService.Qualification qualify(Fno925EntryQualificationService.EvaluationCycle cycle) {
        var value = new AtomicReference<Fno925EntryQualificationService.Qualification>();
        service.withinCycle(cycle, () -> value.set(service.qualifyWithDirectionalCandle(candidate,now.plusSeconds(5))));
        return value.get();
    }
    @Test void accountsShareExactlyOneMarketCalculationPerCycleAndLaterCycleRecalculates() throws Exception {
        var cycle = new Fno925EntryQualificationService.EvaluationCycle(now);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> qualify(cycle));
            var second = executor.submit(() -> qualify(cycle));
            assertThat(first.get(3,TimeUnit.SECONDS)).isSameAs(second.get(3,TimeUnit.SECONDS));
            verify(support,times(1)).loadCandlesWithHistoricalFallback(any(),eq(76));
            qualify(new Fno925EntryQualificationService.EvaluationCycle(now));
            verify(support,times(2)).loadCandlesWithHistoricalFallback(any(),eq(76));
        } finally { executor.shutdownNow(); }
    }
    @Test void directionAndContractAreNotMixedInTheSharedCache() {
        var cycle = new Fno925EntryQualificationService.EvaluationCycle(now);
        service.withinCycle(cycle, () -> {
            service.qualifyWithDirectionalCandle(candidate,now);
            service.qualifyWithDirectionalCandle(new Fno925Candidate("WIPRO",candidate.spot(),"PE"),now);
            service.qualifyWithDirectionalCandle(new Fno925Candidate("OTHER",ScriptMasterEntity.builder().exchange("NC").scripCode(42).build(),"CE"),now);
        });
        verify(support,times(3)).loadCandlesWithHistoricalFallback(any(),eq(76));
    }
    @Test void ageAndCompletedCandleBoundaryInvalidateOldSignals() {
        var cycle = new Fno925EntryQualificationService.EvaluationCycle(now);
        clock(now.plusSeconds(31)); assertThat(qualify(cycle).reason()).contains("expired");
        clock(now.withMinute(40)); assertThat(qualify(new Fno925EntryQualificationService.EvaluationCycle(now.withMinute(39).withSecond(59))).reason()).contains("expired");
        verifyNoInteractions(support);
    }
    @Test void expiryDuringCandleLoadCannotPublishAStaleQualifiedResult() {
        when(support.loadCandlesWithHistoricalFallback(any(),anyInt())).thenAnswer(c -> { clock(now.plusSeconds(31));return new CandleLoad(List.of(),true,null); });
        assertThat(qualify(new Fno925EntryQualificationService.EvaluationCycle(now)).reason()).contains("expired");
    }
    @Test void exceptionsClearThreadContextAndDoNotPoisonNextEvaluation() {
        assertThatThrownBy(() -> service.withinCycle(new Fno925EntryQualificationService.EvaluationCycle(now), () -> { throw new IllegalStateException("test"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.cycleTiming()).isEqualTo("cycle=direct");
        assertThat(service.isCycleExpired()).isFalse();
        service.qualifyWithDirectionalCandle(candidate,now);
        verify(support).loadCandlesWithHistoricalFallback(any(),eq(76));
    }
}
