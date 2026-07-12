package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OrderExecutionDispatcherTest {

    @Test
    void acceptsOnlyOneInFlightTaskForTheSameOrderKey() throws Exception {
        OrderExecutionDispatcher dispatcher = new OrderExecutionDispatcher(1, 1, 10, 10, 1, 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        assertThat(dispatcher.submit("ENTRY:8908", () -> {
            executions.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.submit("ENTRY:8908", executions::incrementAndGet)).isFalse();
        release.countDown();
        Thread.sleep(50);

        assertThat(executions).hasValue(1);
        dispatcher.shutdown();
    }

    @Test
    void exitOrderRunsWhileEntryPoolIsBlocked() throws Exception {
        OrderExecutionDispatcher dispatcher = new OrderExecutionDispatcher(1, 1, 10, 10, 1, 10);
        CountDownLatch entryStarted = new CountDownLatch(1);
        CountDownLatch releaseEntry = new CountDownLatch(1);
        CountDownLatch exitCompleted = new CountDownLatch(1);

        assertThat(dispatcher.submit("ENTRY:1", () -> {
            entryStarted.countDown();
            try {
                releaseEntry.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(entryStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.submit("EXIT:2", exitCompleted::countDown)).isTrue();
        assertThat(exitCompleted.await(1, TimeUnit.SECONDS)).isTrue();

        releaseEntry.countDown();
        dispatcher.shutdown();
    }

    @Test
    void simulatorWorkDoesNotConsumeLiveEntryCapacity() throws Exception {
        OrderExecutionDispatcher dispatcher = new OrderExecutionDispatcher(1, 1, 10, 10, 1, 10);
        CountDownLatch simulatorStarted = new CountDownLatch(1);
        CountDownLatch releaseSimulator = new CountDownLatch(1);
        CountDownLatch liveEntryCompleted = new CountDownLatch(1);

        assertThat(dispatcher.submit("SIM:ENTRY:3", () -> {
            simulatorStarted.countDown();
            try {
                releaseSimulator.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).isTrue();
        assertThat(simulatorStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(dispatcher.submit("ENTRY:4", liveEntryCompleted::countDown)).isTrue();
        assertThat(liveEntryCompleted.await(1, TimeUnit.SECONDS)).isTrue();

        releaseSimulator.countDown();
        dispatcher.shutdown();
    }
}
