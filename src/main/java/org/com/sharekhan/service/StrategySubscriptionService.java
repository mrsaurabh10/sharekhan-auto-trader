package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.com.sharekhan.strategy.Fno0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.Stock0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.AtrPreviousDayFnoCeStrategy;
import org.com.sharekhan.strategy.AtrPreviousDayFnoPeStrategy;
import org.com.sharekhan.strategy.SpotAtrPreviousDayBigTradePlusStrategy;
import org.com.sharekhan.strategy.SpotAtrPreviousDayBigTradePlusSellStrategy;
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
import java.util.Objects;
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
    private final org.com.sharekhan.onboarding.StrategyEnrollmentService enrollment;

    @org.springframework.beans.factory.annotation.Autowired
    private MoverStrategyEvaluationDispatcher moverDispatcher;

    public StrategySubscriptionEntity start(StrategyApplyRequest request) {
        validate(request);
        String templateId = request.getTemplateId().trim().toUpperCase(Locale.ROOT);
        String symbol = isAutomaticUniverseTemplate(templateId)
                ? "FNO_UNIVERSE"
                : request.getSymbol().trim().toUpperCase(Locale.ROOT);

        String source = StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "strategy:" + templateId;
        StrategySubscriptionEntity saved = enrollment.enroll(request, templateId, symbol, source);
        evaluateImmediatelyIfEligible(saved);
        return saved;
    }

    public List<StrategySubscriptionEntity> list(Long appUserId, boolean admin) {
        return list(appUserId,admin,"ACTIVE");
    }

    public List<StrategySubscriptionEntity> list(Long appUserId, boolean admin, String status) {
        String filter=status==null ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
        List<String> statuses=switch(filter) {
            case "ACTIVE" -> List.of(ACTIVE,TRIGGERED);
            case "PAUSED" -> List.of("PAUSED");
            case "CANCELLED" -> List.of(CANCELLED);
            case "ALL" -> List.of();
            default -> throw new IllegalArgumentException("Status must be ACTIVE, PAUSED, CANCELLED or ALL");
        };
        if(!statuses.isEmpty()) {
            if(admin && appUserId==null)return repository.findByStatusInOrderByIdDesc(statuses);
            return appUserId==null ? List.of() : repository.findByAppUserIdAndStatusInOrderByIdDesc(appUserId,statuses);
        }
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

    @org.springframework.transaction.annotation.Transactional
    public StrategySubscriptionEntity cancel(Long id, Long currentUserId, boolean admin) {
        StrategySubscriptionEntity entity = repository.lockForRunEntry(id)
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
        repository.updateConfiguredSymbols(id, normalized, "Configured instruments updated: " + normalized + ".");
        StrategySubscriptionEntity saved = repository.findById(id).orElseThrow();
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
        evaluateBatch(active);
    }

    @Scheduled(cron = "0 30 9,13 * * MON-FRI", zone = "Asia/Kolkata")
    public void enterExpiryStraddleSessions() {
        if (!nseMarketCalendar.isTradingDay(LocalDateTime.now(MARKET_ZONE).toLocalDate())) return;
        evaluateBatch(repository.findByStatusInOrderByIdDesc(List.of(ACTIVE, TRIGGERED)).stream()
                .filter(s -> org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(s.getTemplateId())).toList());
    }

    /** The mover universe must be frozen at the stated market time, not at the next polling tick. */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void snapshotFno0925MoverStrategies() {
        if (!nseMarketCalendar.isTradingDay(LocalDateTime.now(MARKET_ZONE).toLocalDate())) {
            return;
        }
        evaluateBatch(repository.findByStatusInOrderByIdDesc(List.of(ACTIVE, TRIGGERED)).stream()
                .filter(subscription -> MoverStrategyEvaluationDispatcher.supports(subscription.getTemplateId()))
                .toList());
    }

    void evaluateBatch(List<StrategySubscriptionEntity> active) {
        moverDispatcher.dispatchAll(active, this::evaluate);
    }

    void evaluate(StrategySubscriptionEntity subscription) {
        if (!List.of(ACTIVE, TRIGGERED).contains(subscription.getStatus())) return;
        try {
            boolean continuousFnoTemplate = isContinuousFnoTemplate(subscription.getTemplateId());
            if (!continuousFnoTemplate && triggeredToday(subscription)) {
                subscription.setStatus(ACTIVE);
                subscription.setLastEvaluatedAt(LocalDateTime.now(MARKET_ZONE));
                subscription.setLastEvaluationStatus("waiting");
                subscription.setLastMessage("Strategy already triggered today; it will reset for the next trading day unless cancelled.");
                repository.recordEvaluationIfActive(subscription);
                return;
            }

            if (!ACTIVE.equalsIgnoreCase(subscription.getStatus())) {
                subscription.setStatus(ACTIVE);
            }

            StrategyApplyRequest request = new StrategyApplyRequest();
            request.setTemplateId(subscription.getTemplateId());
            request.setSubscriptionId(subscription.getId());
            request.setRiskSettings(subscription.getRiskSettings());
            request.setSymbol(subscription.getSymbol());
            request.setLots(subscription.getLots());
            request.setProfitTargetPerLot(subscription.getProfitTargetPerLot());
            request.setInvestmentPerStock(subscription.getInvestmentPerStock());
            request.setIntraday(subscription.getIntraday());
            request.setUserId(subscription.getAppUserId());
            request.setBrokerCredentialsId(subscription.getBrokerCredentialsId());
            request.setSource(effectiveSource(subscription.getSource(), subscription.getTemplateId()));

            StrategyApplyResponse response = strategyTemplateService.apply(request);
            subscription.setLastEvaluatedAt(LocalDateTime.now());
            subscription.setLastEvaluationStatus(response.getStatus());
            subscription.setLastMessage(response.getMessage());
            if (response.getTradeRequest() != null && (!"duplicate".equalsIgnoreCase(response.getStatus()) || matchingDailyDuplicate(subscription, response))) {
                subscription.setGeneratedTradeRequestId(response.getTradeRequest().getId());
            }
            if (!continuousFnoTemplate && ("triggered".equalsIgnoreCase(response.getStatus()) || ("duplicate".equalsIgnoreCase(response.getStatus()) && matchingDailyDuplicate(subscription, response)))) {
                subscription.setStatus(ACTIVE);
                subscription.setCompletedAt(LocalDateTime.now(MARKET_ZONE));
                subscription.setLastMessage(response.getMessage() + " Strategy remains active and will reset for the next trading day unless cancelled.");
            } else if (continuousFnoTemplate) {
                subscription.setStatus(ACTIVE);
                subscription.setCompletedAt(null);
            }
            repository.recordEvaluationIfActive(subscription);
        } catch (Exception e) {
            subscription.setLastEvaluatedAt(LocalDateTime.now());
            subscription.setLastEvaluationStatus("error");
            subscription.setLastMessage(e.getMessage());
            repository.recordEvaluationIfActive(subscription);
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
        if (!strategyTemplateService.supports(request.getTemplateId())) {
            throw new IllegalArgumentException("Unknown strategy template");
        }
        if (Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId().trim())) {
            request.setLots(null);
        } else {
            if (request.getLots() == null) request.setLots(1);
            if (request.getLots() < 1 || request.getLots() > 1000) {
                throw new IllegalArgumentException("Choose between 1 and 1000 lots");
            }
        }
        if (request.getSymbol() != null && request.getSymbol().length() > 255) {
            throw new IllegalArgumentException("Instrument list is too long (maximum 255 characters)");
        }
        if (org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId().trim())) {
            request.setSymbol(org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.symbol(request.getSymbol()));
            request.setLots(org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.lots(request.getLots()));
            request.setProfitTargetPerLot(org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.profitTarget(request.getProfitTargetPerLot()));
            request.setIntraday(true);
        }
        if (!isAutomaticUniverseTemplate(request.getTemplateId()) && !StringUtils.hasText(request.getSymbol())) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId().trim())) {
            Stock0925MoverAtrBreakoutStrategy.validateInvestment(request);
        }
        request.setRiskSettings(org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(request.getRiskSettings(),request.getLots()));
        if (request.getUserId() == null) {
            throw new IllegalArgumentException("userId is required to start a background strategy");
        }
    }

    private boolean isFnoMoverTemplate(String templateId) {
        return Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    private boolean matchingDailyDuplicate(StrategySubscriptionEntity subscription, StrategyApplyResponse response) {
        var trade = response.getTradeRequest();
        return trade != null && trade.getStatus() != null && trade.getCreatedAt() != null
                && trade.getCreatedAt().toLocalDate().equals(LocalDateTime.now(MARKET_ZONE).toLocalDate())
                && Objects.equals(subscription.getAppUserId(), trade.getAppUserId())
                && Objects.equals(subscription.getBrokerCredentialsId(), trade.getBrokerCredentialsId())
                && subscription.getSymbol().equalsIgnoreCase(trade.getSymbol())
                && (!subscription.getTemplateId().endsWith("_CE") || "CE".equalsIgnoreCase(trade.getOptionType()))
                && (!subscription.getTemplateId().endsWith("_PE") || "PE".equalsIgnoreCase(trade.getOptionType()))
                && trade.getStatus() != org.com.sharekhan.enums.TriggeredTradeStatus.REJECTED
                && trade.getStatus() != org.com.sharekhan.enums.TriggeredTradeStatus.FAILED
                && trade.getStatus() != org.com.sharekhan.enums.TriggeredTradeStatus.CANCELLED
                && expectedTradeSource(subscription).equalsIgnoreCase(trade.getSource());
    }

    private String expectedTradeSource(StrategySubscriptionEntity subscription) {
        if (SpotAtrPreviousDayBigTradePlusStrategy.TEMPLATE_ID.equalsIgnoreCase(subscription.getTemplateId())) {
            return SpotAtrPreviousDayBigTradePlusStrategy.SOURCE;
        }
        if (SpotAtrPreviousDayBigTradePlusSellStrategy.SELL_TEMPLATE_ID.equalsIgnoreCase(subscription.getTemplateId())) {
            return SpotAtrPreviousDayBigTradePlusSellStrategy.SELL_SOURCE;
        }
        return effectiveSource(subscription.getSource(), subscription.getTemplateId());
    }

    private String effectiveSource(String source, String templateId) {
        return StringUtils.hasText(source) ? source.trim() : "strategy:" + templateId;
    }

    private boolean isAutomaticUniverseTemplate(String templateId) {
        return Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId == null ? "" : templateId.trim())
                || isFnoMoverTemplate(templateId)
                || MarketauxSentimentSwingAtrStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId);
    }

    private boolean isContinuousFnoTemplate(String templateId) {
        return isAutomaticUniverseTemplate(templateId)
                || org.com.sharekhan.strategy.straddle.ExpiryStraddleRules.TEMPLATE_ID.equalsIgnoreCase(templateId)
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
        if (subscription == null || !List.of(ACTIVE, TRIGGERED).contains(subscription.getStatus())) return;
        if (!isAtrPreviousDayTemplate(subscription != null ? subscription.getTemplateId() : null)) {
            return;
        }
        evaluate(subscription);
    }

    private boolean isAtrPreviousDayTemplate(String templateId) {
        return AtrPreviousDayFnoCeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || AtrPreviousDayFnoPeStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || SpotAtrPreviousDayBigTradePlusStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId)
                || SpotAtrPreviousDayBigTradePlusSellStrategy.SELL_TEMPLATE_ID.equalsIgnoreCase(templateId);
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
