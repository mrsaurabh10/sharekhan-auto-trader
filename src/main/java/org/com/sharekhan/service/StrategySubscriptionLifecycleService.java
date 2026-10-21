package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.onboarding.UserTradingAccess;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Changes scheduling only. Existing requests, run identity and exit monitoring are retained. */
@Service
@RequiredArgsConstructor
public class StrategySubscriptionLifecycleService {
    private final StrategySubscriptionRepository subscriptions;
    private final AppUserRepository users;
    private final UserTradingAccess access;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public StrategySubscriptionEntity pause(Long id, Long userId, boolean admin) {
        var subscription = subscriptions.lockForRunEntry(id).orElseThrow(this::missing);
        entityManager.refresh(subscription);
        requireOwner(subscription, userId, admin);
        if ("PAUSED".equals(subscription.getStatus())) return subscription;
        if (!List.of("ACTIVE", "TRIGGERED").contains(subscription.getStatus())) throw invalidState();
        subscription.setStatus("PAUSED");
        subscription.setLastMessage("Strategy paused. Existing orders and positions continue to be managed.");
        return subscriptions.save(subscription);
    }

    @Transactional
    public StrategySubscriptionEntity resume(Long id, Long userId, boolean admin) {
        var observed = subscriptions.findById(id).orElseThrow(this::missing);
        requireOwner(observed, userId, admin);
        // Same lock order as enrollment; starting and resuming cannot create duplicate schedules.
        users.lockForEnrollment(observed.getAppUserId()).orElseThrow(this::missing);
        var subscription = subscriptions.lockForRunEntry(id).orElseThrow(this::missing);
        entityManager.refresh(subscription);
        requireOwner(subscription, userId, admin);
        if (List.of("ACTIVE", "TRIGGERED").contains(subscription.getStatus())) return subscription;
        if (!"PAUSED".equals(subscription.getStatus())) throw invalidState();
        if (admin) access.requireOnboardedReady(subscription.getAppUserId(), subscription.getBrokerCredentialsId());
        else access.requireReady(subscription.getAppUserId(), subscription.getBrokerCredentialsId());
        subscription.setStatus("ACTIVE");
        subscription.setLastMessage("Strategy resumed. Evaluation continues on the next scheduled cycle.");
        // In particular, keep completedAt: pausing must not bypass today's entry limit.
        return subscriptions.save(subscription);
    }

    private void requireOwner(StrategySubscriptionEntity subscription, Long userId, boolean admin) {
        if (!admin && (userId == null || !userId.equals(subscription.getAppUserId())))
            throw new AccessDeniedException("Strategy does not belong to you");
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy subscription not found");
    }

    private ResponseStatusException invalidState() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Only active strategies can be paused; only paused strategies can be resumed");
    }
}
