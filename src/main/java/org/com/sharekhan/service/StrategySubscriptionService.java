package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySubscriptionService {

    private static final String ACTIVE = "ACTIVE";
    private static final String TRIGGERED = "TRIGGERED";
    private static final String CANCELLED = "CANCELLED";
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final StrategySubscriptionRepository repository;
    private final StrategyTemplateService strategyTemplateService;

    public StrategySubscriptionEntity start(StrategyApplyRequest request) {
        validate(request);
        String templateId = request.getTemplateId().trim().toUpperCase(Locale.ROOT);
        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);

        if (request.getUserId() != null) {
            List<StrategySubscriptionEntity> existing = repository
                    .findByStatusAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(ACTIVE, templateId, symbol, request.getUserId());
            if (existing != null && !existing.isEmpty()) {
                return existing.get(0);
            }
        }

        StrategySubscriptionEntity entity = StrategySubscriptionEntity.builder()
                .templateId(templateId)
                .symbol(symbol)
                .lots(request.getLots())
                .intraday(request.getIntraday() != null ? request.getIntraday() : true)
                .appUserId(request.getUserId())
                .brokerCredentialsId(request.getBrokerCredentialsId())
                .source(StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + templateId)
                .status(ACTIVE)
                .lastMessage("Strategy started and waiting for ORB confirmation.")
                .build();
        return repository.save(entity);
    }

    public List<StrategySubscriptionEntity> list(Long appUserId, boolean admin) {
        if (admin && appUserId == null) {
            return repository.findAll().stream()
                    .sorted((a, b) -> Long.compare(b.getId() != null ? b.getId() : 0L, a.getId() != null ? a.getId() : 0L))
                    .toList();
        }
        if (appUserId == null) {
            return List.of();
        }
        return repository.findByAppUserIdOrderByIdDesc(appUserId);
    }

    public StrategySubscriptionEntity cancel(Long id, Long currentUserId, boolean admin) {
        StrategySubscriptionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Strategy subscription not found: " + id));
        if (!admin && (currentUserId == null || entity.getAppUserId() == null || !currentUserId.equals(entity.getAppUserId()))) {
            throw new IllegalArgumentException("Forbidden: strategy does not belong to user");
        }
        if (!ACTIVE.equalsIgnoreCase(entity.getStatus())) {
            return entity;
        }
        entity.setStatus(CANCELLED);
        entity.setCompletedAt(LocalDateTime.now());
        entity.setLastMessage("Strategy cancelled by user.");
        return repository.save(entity);
    }

    @Scheduled(fixedDelayString = "${app.strategy.scheduler-delay-ms:60000}")
    public void evaluateActiveStrategies() {
        LocalTime now = LocalTime.now(MARKET_ZONE);
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(15, 25))) {
            return;
        }
        List<StrategySubscriptionEntity> active = repository.findByStatusOrderByIdDesc(ACTIVE);
        for (StrategySubscriptionEntity subscription : active) {
            evaluate(subscription);
        }
    }

    private void evaluate(StrategySubscriptionEntity subscription) {
        try {
            StrategyApplyRequest request = new StrategyApplyRequest();
            request.setTemplateId(subscription.getTemplateId());
            request.setSymbol(subscription.getSymbol());
            request.setLots(subscription.getLots());
            request.setIntraday(subscription.getIntraday());
            request.setUserId(subscription.getAppUserId());
            request.setBrokerCredentialsId(subscription.getBrokerCredentialsId());
            request.setSource("strategy:subscription:" + subscription.getId() + ":" + subscription.getTemplateId());

            StrategyApplyResponse response = strategyTemplateService.apply(request);
            subscription.setLastEvaluatedAt(LocalDateTime.now());
            subscription.setLastEvaluationStatus(response.getStatus());
            subscription.setLastMessage(response.getMessage());
            if (response.getTradeRequest() != null) {
                subscription.setGeneratedTradeRequestId(response.getTradeRequest().getId());
            }
            if ("triggered".equalsIgnoreCase(response.getStatus()) || "duplicate".equalsIgnoreCase(response.getStatus())) {
                subscription.setStatus(TRIGGERED);
                subscription.setCompletedAt(LocalDateTime.now());
            }
            repository.save(subscription);
        } catch (Exception e) {
            subscription.setLastEvaluatedAt(LocalDateTime.now());
            subscription.setLastEvaluationStatus("error");
            subscription.setLastMessage(e.getMessage());
            repository.save(subscription);
            log.warn("Strategy subscription {} evaluation failed: {}", subscription.getId(), e.getMessage());
        }
    }

    private void validate(StrategyApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!StringUtils.hasText(request.getTemplateId())) {
            throw new IllegalArgumentException("templateId is required");
        }
        if (!StringUtils.hasText(request.getSymbol())) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId is required to start a background strategy");
        }
    }
}
