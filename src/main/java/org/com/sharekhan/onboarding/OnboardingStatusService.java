package org.com.sharekhan.onboarding;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingStatusService {
    private final CurrentUserService current;
    private final AccountProfileRepository profiles;
    private final BrokerCredentialsRepository brokers;
    private final BrokerConnectionRepository connections;
    private final StrategySubscriptionRepository subscriptions;
    private final TriggerTradeRequestRepository requests;

    public record Step(String id, String title, boolean complete, String destination) {}
    public record Status(boolean accountReady, long connectedBrokers, long tradingReadyBrokers,
                         long activeStrategies, long orderRequests, String nextStep,
                         List<Step> steps) {}

    @Transactional(readOnly = true)
    public Status status() {
        Long userId = current.requireAppUserId();
        boolean accountReady = profiles.findById(userId).map(p -> p.getVerifiedAt() != null).orElse(true);
        var owned = brokers.findByAppUserId(userId);
        var verified = connections.findAllById(owned.stream().map(b -> b.getId()).toList()).stream()
                .filter(c -> c.getVerifiedUntil() != null && c.getVerifiedUntil().isAfter(Instant.now()))
                .map(BrokerConnection::getCredentialId).collect(java.util.stream.Collectors.toSet());
        var connected = owned.stream().filter(b -> Boolean.TRUE.equals(b.getActive())
                && BrokerCatalog.supports(b.getBrokerName())
                && ("Dhan".equalsIgnoreCase(b.getBrokerName()) || (b.getCustomerId() != null && b.getCustomerId() > 0))
                && verified.contains(b.getId())).toList();
        long tradingReady = connected.stream().filter(b -> BrokerCatalog.tradingSupported(b.getBrokerName()) && Boolean.TRUE.equals(b.getTradingEnabled())).count();
        long active = subscriptions.countByAppUserIdAndStatusIn(userId, List.of("ACTIVE", "TRIGGERED"));
        long orders = requests.countByAppUserId(userId);
        List<Step> steps = List.of(
                new Step("ACCOUNT", "Your account is ready", accountReady, "settings"),
                new Step("BROKER", "Connect your broker", !connected.isEmpty(), "brokers"),
                new Step("TRADING", "Choose when to enable trading", tradingReady > 0, "brokers"),
                new Step("WORKFLOW", "Create your trading workflow", active > 0 || orders > 0, "strategies"));
        String next = steps.stream().filter(s -> !s.complete()).map(Step::id).findFirst().orElse("COMPLETE");
        return new Status(accountReady, connected.size(), tradingReady, active, orders, next, steps);
    }
}
