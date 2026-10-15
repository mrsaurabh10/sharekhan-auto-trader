package org.com.sharekhan.onboarding;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.SharekhanAuthProvider;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.springframework.http.HttpStatus.*;

@Service @RequiredArgsConstructor
public class BrokerConnectionService {
    private final UserTradingAccess access;
    private final BrokerCredentialsRepository brokers;
    private final BrokerConnectionRepository connections;
    private final SharekhanAuthProvider provider;
    private final AccessTokenPersistenceService tokens;
    private final StrategySubscriptionRepository subscriptions;
    private final TriggeredTradeSetupRepository trades;
    private final TriggerTradeRequestRepository requests;
    @Transactional
    public Map<String, Object> connect(Long id) {
        var broker = access.owned(id);
        BrokerCatalog.requireSupported(broker.getBrokerName());
        if (broker.getCustomerId() == null || broker.getCustomerId() <= 0)
            throw new ResponseStatusException(BAD_REQUEST, "Add the Sharekhan customer ID before verifying this connection");
        try {
            var result = provider.loginAndFetchToken(broker);
            if (result == null || result.token() == null || result.token().isBlank() || result.expiresIn() <= 60)
                throw new IllegalStateException("Invalid session");
            Instant until = Instant.now().plusSeconds(result.expiresIn() - 60);
            tokens.replaceTokenForBrokerCredentials("Sharekhan", id, result.token(), until);
            BrokerConnection connection = connections.findById(id).orElseGet(BrokerConnection::new);
            connection.setCredentialId(id); connection.setVerifiedUntil(until); connections.save(connection);
            broker.setActive(true); brokers.save(broker);
            return Map.of("message", "Sharekhan connection verified. You can enable trading.", "verifiedUntil", until);
        } catch (Exception ex) {
            // Do not expose broker exceptions or submitted credentials to the browser.
            throw new ResponseStatusException(BAD_REQUEST, "Sharekhan connection failed. Check your credentials and try again.");
        }
    }
    @Transactional
    public void setTrading(Long id, boolean enabled) {
        var broker = access.owned(id);
        if (enabled && (broker.getCustomerId() == null || broker.getCustomerId() <= 0 || !access.connected(id) || !Boolean.TRUE.equals(broker.getActive())))
            throw new ResponseStatusException(BAD_REQUEST, "Verify your connection before enabling trading");
        broker.setTradingEnabled(enabled); brokers.save(broker);
    }
    @Transactional
    public void disconnect(Long id) {
        var broker = access.owned(id);
        boolean running = subscriptions.findByAppUserIdOrderByIdDesc(broker.getAppUserId()).stream()
            .anyMatch(s -> id.equals(s.getBrokerCredentialsId()) && !"CANCELLED".equals(s.getStatus()));
        var entryStates = List.of(TriggeredTradeStatus.TRIGGERED, TriggeredTradeStatus.ENTRY_SUBMITTING,
            TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        boolean pending = requests.existsByBrokerCredentialsIdAndStatusIn(id, entryStates);
        boolean open = trades.existsByBrokerCredentialsIdAndStatusIn(id, List.of(TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.ENTRY_SUBMITTING, TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION,
            TriggeredTradeStatus.TARGET_ORDER_PLACED, TriggeredTradeStatus.EXIT_ORDER_PLACED,
            TriggeredTradeStatus.EXIT_TRIGGERED, TriggeredTradeStatus.EXIT_FAILED));
        if (running || pending || open) throw new ResponseStatusException(CONFLICT, "Cancel subscriptions and pending orders, and close open positions before disconnecting");
        broker.setActive(false); broker.setTradingEnabled(false); broker.setDefaultForOrders(false); brokers.save(broker);
        connections.findById(id).ifPresent(c -> { c.setVerifiedUntil(Instant.EPOCH); connections.save(c); });
    }
}
