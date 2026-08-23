package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.strategy.Fno0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.AtrPreviousDayFnoCeStrategy;
import org.com.sharekhan.strategy.AtrPreviousDayFnoPeStrategy;
import org.com.sharekhan.strategy.ManualFnoVwapReclaimCeStrategy;
import org.com.sharekhan.strategy.ManualFnoVwapReclaimPeStrategy;
import org.com.sharekhan.strategy.MarketauxSentimentSwingAtrStrategy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
    private final NseMarketCalendar nseMarketCalendar;

    public StrategySubscriptionEntity start(StrategyApplyRequest request) {
        validate(request);
        String templateId = request.getTemplateId().trim().toUpperCase(Locale.ROOT);
        String symbol = isAutomaticUniverseTemplate(templateId)
                ? "FNO_UNIVERSE"
                : request.getSymbol().trim().toUpperCase(Locale.ROOT);

        if (request.getUserId() != null) {
            List<StrategySubscriptionEntity> existing = repository
                    .findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
                            List.of(ACTIVE, TRIGGERED), templateId, symbol, request.getUserId());
            if (existing != null && !existing.isEmpty()) {
                StrategySubscriptionEntity found = existing.get(0);
                if (!ACTIVE.equalsIgnoreCase(found.getStatus())) {
                    found.setStatus(ACTIVE);
                    found.setLastMessage("Strategy is active and will run daily until cancelled.");
                    found = repository.save(found);
                }
                evaluateImmediatelyIfEligible(found);
                return found;
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
                .lastMessage("Strategy started and waiting for confirmation.")
                .build();
        StrategySubscriptionEntity saved = repository.save(entity);
        evaluateImmediatelyIfEligible(saved);
        return saved;
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
        if (CANCELLED.equalsIgnoreCase(entity.getStatus())) {
            return entity;
        }
        entity.setStatus(CANCELLED);
        entity.setCompletedAt(LocalDateTime.now());
        entity.setLastMessage("Strategy cancelled by user.");
        return repository.save(entity);
    }

    public StrategySubscriptionEntity updateSymbols(Long id, String symbols, Long currentUserId, boolean admin) {
        StrategySubscriptionEntity entity = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Strategy subscription not found: " + id));
        if (!admin && (currentUserId == null || entity.getAppUserId() == null || !currentUserId.equals(entity.getAppUserId()))) {
            throw new IllegalArgumentException("Forbidden: strategy does not belong to user");
        }
        if (!isManualFnoTemplate(entity.getTemplateId())) {
            throw new IllegalArgumentException("Only manually curated F&O CE/PE templates support symbol-list updates");
        }
        String normalized = normalizeSymbolList(symbols);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Enter one or more symbols separated by commas, semicolons, or new lines");
        }
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("Configured symbol list is too long (maximum 255 characters)");
        }
        entity.setSymbol(normalized);
        entity.setLastMessage("Configured instruments updated: " + normalized + ".");
        StrategySubscriptionEntity saved = repository.save(entity);
        evaluateImmediatelyIfEligible(saved);
        return saved;
    }

    @Scheduled(fixedDelayString = "${app.strategy.scheduler-delay-ms:60000}")
    public void evaluateActiveStrategies() {
        LocalTime now = LocalTime.now(MARKET_ZONE);
        if (!nseMarketCalendar.isTradingDay(LocalDateTime.now(MARKET_ZONE).toLocalDate())
                || now.isBefore(LocalTime.of(9, 25)) || now.isAfter(LocalTime.of(15, 25))) {
            return;
        }
        List<StrategySubscriptionEntity> active = repository.findByStatusInOrderByIdDesc(List.of(ACTIVE, TRIGGERED));
        for (StrategySubscriptionEntity subscription : active) {
            evaluate(subscription);
        }
    }

    /** The mover universe must be frozen at the stated market time, not at the next polling tick. */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void snapshotFno0925MoverStrategies() {
        if (!nseMarketCalendar.isTradingDay(LocalDateTime.now(MARKET_ZONE).toLocalDate())) {
            return;
        }
        repository.findByStatusInOrderByIdDesc(List.of(ACTIVE, TRIGGERED)).stream()
                .filter(subscription -> Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID
                        .equalsIgnoreCase(subscription.getTemplateId()))
                .forEach(this::evaluate);
    }

    private void evaluate(StrategySubscriptionEntity subscription) {
        try {
            boolean continuousFnoTemplate = isContinuousFnoTemplate(subscription.getTemplateId());
            if (!continuousFnoTemplate && triggeredToday(subscription)) {
                subscription.setStatus(ACTIVE);
                subscription.setLastEvaluatedAt(LocalDateTime.now(MARKET_ZONE));
                subscription.setLastEvaluationStatus("waiting");
                subscription.setLastMessage("Strategy already triggered today; it will reset for the next trading day unless cancelled.");
                repository.save(subscription);
                return;
            }

            if (!ACTIVE.equalsIgnoreCase(subscription.getStatus())) {
                subscription.setStatus(ACTIVE);
            }

            StrategyApplyRequest request = new StrategyApplyRequest();
            request.setTemplateId(subscription.getTemplateId());
            request.setSymbol(subscription.getSymbol());
            request.setLots(subscription.getLots());
            request.setIntraday(subscription.getIntraday());
            request.setUserId(subscription.getAppUserId());
            request.setBrokerCredentialsId(subscription.getBrokerCredentialsId());
            request.setSource("strategy:" + subscription.getTemplateId());

            StrategyApplyResponse response = strategyTemplateService.apply(request);
            subscription.setLastEvaluatedAt(LocalDateTime.now());
            subscription.setLastEvaluationStatus(response.getStatus());
            subscription.setLastMessage(response.getMessage());
            if (response.getTradeRequest() != null) {
                subscription.setGeneratedTradeRequestId(response.getTradeRequest().getId());
            }
            if (!continuousFnoTemplate && ("triggered".equalsIgnoreCase(response.getStatus()) || "duplicate".equalsIgnoreCase(response.getStatus()))) {
                subscription.setStatus(ACTIVE);
                subscription.setCompletedAt(LocalDateTime.now(MARKET_ZONE));
                subscription.setLastMessage(response.getMessage() + " Strategy remains active and will reset for the next trading day unless cancelled.");
            } else if (continuousFnoTemplate) {
                subscription.setStatus(ACTIVE);
                subscription.setCompletedAt(null);
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

    private boolean triggeredToday(StrategySubscriptionEntity subscription) {
        if (subscription == null || subscription.getCompletedAt() == null) {
            return false;
        }
        return subscription.getCompletedAt().toLocalDate().equals(LocalDateTime.now(MARKET_ZONE).toLocalDate());
    }

    private void validate(StrategyApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!StringUtils.hasText(request.getTemplateId())) {
            throw new IllegalArgumentException("templateId is required");
        }
        if (!isAutomaticUniverseTemplate(request.getTemplateId()) && !StringUtils.hasText(request.getSymbol())) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId is required to start a background strategy");
        }
    }

    private boolean isFnoMoverTemplate(String templateId) {
        return Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    private boolean isAutomaticUniverseTemplate(String templateId) {
        return isFnoMoverTemplate(templateId)
                || MarketauxSentimentSwingAtrStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    private boolean isContinuousFnoTemplate(String templateId) {
        return isAutomaticUniverseTemplate(templateId)
                || isManualFnoTemplate(templateId);
    }

    private boolean isManualFnoTemplate(String templateId) {
        return ManualFnoVwapReclaimCeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || ManualFnoVwapReclaimPeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || AtrPreviousDayFnoCeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || AtrPreviousDayFnoPeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    /** New ATR subscriptions should create their pending request immediately instead of waiting for the next poll. */
    private void evaluateImmediatelyIfEligible(StrategySubscriptionEntity subscription) {
        if (!isAtrPreviousDayTemplate(subscription != null ? subscription.getTemplateId() : null)) {
            return;
        }
        evaluate(subscription);
    }

    private boolean isAtrPreviousDayTemplate(String templateId) {
        return AtrPreviousDayFnoCeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || AtrPreviousDayFnoPeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    private String normalizeSymbolList(String symbols) {
        if (!StringUtils.hasText(symbols)) {
            return "";
        }
        return java.util.Arrays.stream(symbols.split("[,;\\n\\r]+"))
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining(","));
    }
}
