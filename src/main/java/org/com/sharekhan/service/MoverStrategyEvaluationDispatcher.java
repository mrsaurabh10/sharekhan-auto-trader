package org.com.sharekhan.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.strategy.Fno0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.Stock0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.Fno925EntryQualificationService;
import org.com.sharekhan.strategy.StrategySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded account evaluations, with live and simulated broker work isolated from each other. */
@Slf4j
@Component
public class MoverStrategyEvaluationDispatcher {
    private final StrategySubscriptionRepository subscriptions;
    private final BrokerCredentialsRepository brokers;
    private final Fno925EntryQualificationService qualifications;
    private final ThreadPoolExecutor live;
    private final ThreadPoolExecutor simulator;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public MoverStrategyEvaluationDispatcher(StrategySubscriptionRepository subscriptions,
            BrokerCredentialsRepository brokers, Fno925EntryQualificationService qualifications,
            @Value("${app.strategy.mover-evaluation.live-pool-size:4}") int livePoolSize,
            @Value("${app.strategy.mover-evaluation.simulator-pool-size:2}") int simulatorPoolSize,
            @Value("${app.strategy.mover-evaluation.queue-capacity:50}") int queueCapacity) {
        this.subscriptions = subscriptions;
        this.brokers = brokers;
        this.qualifications = qualifications;
        live = executor("mover-live", livePoolSize, queueCapacity);
        simulator = executor("mover-simulator", simulatorPoolSize, queueCapacity);
    }

    public static boolean supports(String templateId) {
        return Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    public void dispatch(List<StrategySubscriptionEntity> active, Consumer<StrategySubscriptionEntity> evaluate) {
        var cycle = new Fno925EntryQualificationService.EvaluationCycle(LocalDateTime.now(StrategySupport.MARKET_ZONE));
        for (StrategySubscriptionEntity subscription : active) {
            if (supports(subscription.getTemplateId())) submit(subscription, cycle, evaluate);
        }
    }

    boolean submit(StrategySubscriptionEntity subscription, Fno925EntryQualificationService.EvaluationCycle cycle,
                   Consumer<StrategySubscriptionEntity> evaluate) {
        if (subscription.getId() == null || subscription.getAppUserId() == null
                || subscription.getBrokerCredentialsId() == null) return false;
        String key = subscription.getTemplateId().toUpperCase(Locale.ROOT) + ":"
                + subscription.getAppUserId() + ":" + subscription.getBrokerCredentialsId();
        if (!inFlight.add(key)) return false;
        try {
            var broker = brokers.findById(subscription.getBrokerCredentialsId())
                    .filter(b -> Objects.equals(b.getAppUserId(), subscription.getAppUserId()))
                    .orElseThrow(() -> new IllegalStateException("Mover broker ownership unavailable"));
            ThreadPoolExecutor executor = "Simulator".equalsIgnoreCase(broker.getBrokerName()) ? simulator : live;
            executor.execute(() -> {
                try {
                    qualifications.withinCycle(cycle, () -> {
                        if (qualifications.isCycleExpired()) {
                            log.info("MOVER_EVALUATION_SKIPPED | subscriptionId={} | reason=EXPIRED | {}",
                                    subscription.getId(), qualifications.cycleTiming());
                            return;
                        }
                        // A queued detached entity must not reactivate a paused subscription or use old settings.
                        subscriptions.findById(subscription.getId())
                                .filter(s -> "ACTIVE".equals(s.getStatus()) || "TRIGGERED".equals(s.getStatus()))
                                .filter(s -> Objects.equals(s.getAppUserId(), subscription.getAppUserId())
                                        && Objects.equals(s.getBrokerCredentialsId(), subscription.getBrokerCredentialsId())
                                        && Objects.equals(s.getTemplateId(), subscription.getTemplateId()))
                                .ifPresent(s -> {
                                    log.info("MOVER_EVALUATION_START | subscriptionId={} | userId={} | brokerCredentialsId={} | {}",
                                            s.getId(), s.getAppUserId(), s.getBrokerCredentialsId(), qualifications.cycleTiming());
                                    evaluate.accept(s);
                                });
                    });
                } catch (Exception error) {
                    log.error("Mover evaluation failed for subscription {}", subscription.getId(), error);
                } finally {
                    inFlight.remove(key);
                }
            });
            return true;
        } catch (RuntimeException error) {
            inFlight.remove(key);
            // Never run rejected tasks on the scheduler thread or create an unbounded overflow queue.
            log.warn("MOVER_EVALUATION_DEFERRED | subscriptionId={} | cycle={} | reason={}",
                    subscription.getId(), cycle.id(), error.getClass().getSimpleName());
            return false;
        }
    }

    private static ThreadPoolExecutor executor(String name, int size, int capacity) {
        return new ThreadPoolExecutor(Math.max(1, size), Math.max(1, size), 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, capacity)), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        live.shutdown();
        simulator.shutdown();
    }
}
