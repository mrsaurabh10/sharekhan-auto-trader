package org.com.sharekhan.service;

import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.strategy.Fno925EntryQualificationService;
import org.com.sharekhan.strategy.Stock0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.StrategySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MoverStrategyEvaluationDispatcherTest {
    final StrategySubscriptionRepository subscriptions = mock(StrategySubscriptionRepository.class);
    final BrokerCredentialsRepository brokers = mock(BrokerCredentialsRepository.class);
    final Fno925EntryQualificationService qualifications = mock(Fno925EntryQualificationService.class);
    final MoverStrategyEvaluationDispatcher dispatcher = new MoverStrategyEvaluationDispatcher(subscriptions, brokers, qualifications, 1, 1, 1);
    final CountDownLatch release = new CountDownLatch(1);

    MoverStrategyEvaluationDispatcherTest() {
        doAnswer(c -> { ((Runnable)c.getArgument(1)).run(); return null; }).when(qualifications).withinCycle(any(), any());
    }
    @AfterEach void stop() { release.countDown(); dispatcher.shutdown(); }

    StrategySubscriptionEntity subscription(long user, String broker) {
        var s = StrategySubscriptionEntity.builder().id(user).appUserId(user).brokerCredentialsId(user)
                .templateId(Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID).status("ACTIVE").build();
        when(brokers.findById(user)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(user).appUserId(user).brokerName(broker).build()));
        when(subscriptions.findById(user)).thenReturn(Optional.of(s));
        return s;
    }
    Fno925EntryQualificationService.EvaluationCycle cycle() {
        return new Fno925EntryQualificationService.EvaluationCycle(LocalDateTime.now(StrategySupport.MARKET_ZONE));
    }
    void await(CountDownLatch latch) {
        try { assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    @Test void slowLiveEvaluationDoesNotDelaySimulatorAndBothReceiveSameCycle() {
        var live = subscription(1, "Sharekhan"); var sim = subscription(3, "Simulator");
        var started = new CountDownLatch(1); var simulated = new CountDownLatch(1);
        dispatcher.dispatch(List.of(live, sim), s -> {
            if (s.getAppUserId()==1L) { started.countDown(); await(release); }
            else simulated.countDown();
        });
        await(started); await(simulated);
        assertThat(release.getCount()).isOne();
        var cycles = org.mockito.ArgumentCaptor.forClass(Fno925EntryQualificationService.EvaluationCycle.class);
        verify(qualifications, times(2)).withinCycle(cycles.capture(), any());
        assertThat(cycles.getAllValues().get(0)).isSameAs(cycles.getAllValues().get(1));
    }

    @Test void overlappingCronAndPollCannotEvaluateTheSameAccountConcurrently() {
        var sub = subscription(1, "Sharekhan"); var started = new CountDownLatch(1);
        Consumer<StrategySubscriptionEntity> evaluate = s -> { started.countDown(); await(release); };
        assertThat(dispatcher.submit(sub, cycle(), evaluate)).isTrue(); await(started);
        assertThat(dispatcher.submit(sub, cycle(), evaluate)).isFalse();
        var duplicate = StrategySubscriptionEntity.builder().id(99L).appUserId(1L).brokerCredentialsId(1L)
                .templateId(sub.getTemplateId()).status("ACTIVE").build();
        assertThat(dispatcher.submit(duplicate, cycle(), evaluate)).isFalse();
    }

    @Test void queueIsBoundedAndCancelledQueuedSubscriptionsNeverExecute() throws Exception {
        var first = subscription(1,"Sharekhan"); var second = subscription(2,"Sharekhan"); var third = subscription(3,"Sharekhan");
        var started = new CountDownLatch(1); var drained = new CountDownLatch(1);
        Consumer<StrategySubscriptionEntity> evaluate = mock(Consumer.class);
        doAnswer(c -> { started.countDown(); await(release); return null; }).when(evaluate).accept(first);
        assertThat(dispatcher.submit(first,cycle(),evaluate)).isTrue(); await(started);
        assertThat(dispatcher.submit(second,cycle(),evaluate)).isTrue();
        assertThat(dispatcher.submit(third,cycle(),evaluate)).isFalse();
        when(subscriptions.findById(2L)).thenAnswer(c -> { drained.countDown(); return Optional.of(
                StrategySubscriptionEntity.builder().id(2L).status("CANCELLED").build()); });
        release.countDown(); await(drained);
        var executor = (java.util.concurrent.ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"live");
        executor.submit(() -> {}).get(3,TimeUnit.SECONDS);
        verify(evaluate, never()).accept(second); verify(evaluate, never()).accept(third);
        var retried = new CountDownLatch(1);
        assertThat(dispatcher.submit(third,cycle(),s -> retried.countDown())).isTrue(); await(retried);
    }

    @Test void expiredCycleSkipsEvenActiveSubscriptionBeforeEntryWork() {
        var sub = subscription(1,"Simulator"); var checked = new CountDownLatch(1);
        when(qualifications.isCycleExpired()).thenAnswer(c -> { checked.countDown(); return true; });
        Consumer<StrategySubscriptionEntity> evaluate = mock(Consumer.class);
        assertThat(dispatcher.submit(sub,cycle(),evaluate)).isTrue(); await(checked);
        verifyNoInteractions(evaluate); verify(subscriptions,never()).findById(anyLong());
    }

    @Test void workerUsesFreshSettingsInsteadOfTheQueuedDetachedSubscription() {
        var old = subscription(1,"Simulator");
        old.setInvestmentPerStock(new java.math.BigDecimal("500000"));
        var fresh = StrategySubscriptionEntity.builder().id(old.getId()).appUserId(old.getAppUserId())
                .brokerCredentialsId(old.getBrokerCredentialsId()).templateId(old.getTemplateId()).status("ACTIVE")
                .investmentPerStock(new java.math.BigDecimal("2000")).build();
        when(subscriptions.findById(1L)).thenReturn(Optional.of(fresh));
        var checked = new CountDownLatch(1);
        var actual = new java.util.concurrent.atomic.AtomicReference<StrategySubscriptionEntity>();
        assertThat(dispatcher.submit(old,cycle(),s -> { actual.set(s); checked.countDown(); })).isTrue(); await(checked);
        assertThat(actual.get()).isSameAs(fresh);
    }

    @Test void nextSubmissionIsPossibleAfterFailure() {
        var sub = subscription(1,"Sharekhan"); var completed = new CountDownLatch(1); var started = new CountDownLatch(1);
        assertThat(dispatcher.submit(sub,cycle(),s -> { started.countDown(); throw new IllegalStateException("test failure"); })).isTrue();
        await(started);
        // Joining the executor makes completion deterministic without sleeps or repeated submissions.
        var executor = (java.util.concurrent.ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"live");
        executor.submit(completed::countDown); await(completed);
        var retried = new CountDownLatch(1);
        assertThat(dispatcher.submit(sub,cycle(),s -> retried.countDown())).isTrue(); await(retried);
    }
}
