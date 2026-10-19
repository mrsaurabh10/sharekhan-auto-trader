package org.com.sharekhan.onboarding;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.strategy.Stock0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes enrollment per account. Broker evaluation happens after this transaction commits. */
@Service
@RequiredArgsConstructor
public class StrategyEnrollmentService {
    private final StrategySubscriptionRepository subscriptions;
    private final AppUserRepository users;

    @Transactional
    public StrategySubscriptionEntity enroll(StrategyApplyRequest request, String templateId,
                                              String symbol, String source) {
        boolean stockMover = Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
        boolean straddle = org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(templateId);
        var profitTarget = straddle ? org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.profitTarget(request.getProfitTargetPerLot()) : null;
        if (stockMover) Stock0925MoverAtrBreakoutStrategy.validateInvestment(request);
        var risk=org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(request.getRiskSettings(),request.getLots());
        users.lockForEnrollment(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        var matches = subscriptions.findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
                List.of("ACTIVE", "TRIGGERED"), templateId, symbol, request.getUserId());
        var existing = matches.stream()
                .filter(s -> Objects.equals(s.getBrokerCredentialsId(), request.getBrokerCredentialsId()))
                .filter(s -> (s.getSource() == null || s.getSource().isBlank() ? "strategy:" + templateId : s.getSource().trim()).equalsIgnoreCase(source))
                .findFirst();
        if (existing.isPresent()) {
            var subscription = existing.get();
            subscription.setRiskSettings(risk);
            if (straddle) {
                subscription.setProfitTargetPerLot(profitTarget);
                subscription.setLots(request.getLots());
            }
            if (stockMover) {
                subscription.setInvestmentPerStock(request.getInvestmentPerStock());
                subscription.setLots(null);
            }
            if (!"ACTIVE".equalsIgnoreCase(subscription.getStatus())) {
                subscription.setStatus("ACTIVE");
                subscription.setLastMessage("Strategy is active and will run daily until cancelled.");
                return subscriptions.save(subscription);
            }
            return subscriptions.save(subscription);
        }
        return subscriptions.save(StrategySubscriptionEntity.builder()
                .templateId(templateId).symbol(symbol).lots(stockMover ? null : request.getLots())
                .investmentPerStock(stockMover ? request.getInvestmentPerStock() : null)
                .profitTargetPerLot(profitTarget).riskSettings(risk)
                .intraday(request.getIntraday() == null || request.getIntraday())
                .appUserId(request.getUserId()).brokerCredentialsId(request.getBrokerCredentialsId())
                .source(source).status("ACTIVE").lastMessage("Strategy started and waiting for confirmation.").build());
    }
}
