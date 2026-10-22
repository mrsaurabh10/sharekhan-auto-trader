package org.com.sharekhan.service;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.StrategyTemplateResponse;
import org.com.sharekhan.strategy.StrategyEvaluator;
import org.com.sharekhan.strategy.StrategyMetadata;
import org.com.sharekhan.strategy.Fno0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.Stock0925MoverAtrBreakoutStrategy;
import org.com.sharekhan.strategy.MarketauxSentimentSwingAtrStrategy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@lombok.extern.slf4j.Slf4j
@Service
public class StrategyTemplateService {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.com.sharekhan.strategy.run.StrategyRunCoordinator runCoordinator;
    @org.springframework.beans.factory.annotation.Autowired
    private org.com.sharekhan.strategy.SharedMarketDecisions sharedDecisions;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private TradeAuditService tradeAudit;
    private final Map<String, StrategyEvaluator> evaluators;

    public StrategyTemplateService(List<StrategyEvaluator> evaluators) {
        this.evaluators = evaluators.stream()
                .collect(Collectors.toUnmodifiableMap(
                        evaluator -> normalizeTemplateId(evaluator.metadata().id()),
                        Function.identity()));
    }

    public boolean supports(String templateId) {
        return templateId != null && evaluators.containsKey(normalizeTemplateId(templateId));
    }

    public List<StrategyTemplateResponse> listTemplates() {
        return evaluators.values().stream()
                .map(StrategyEvaluator::metadata)
                .sorted(Comparator.comparing(StrategyMetadata::id))
                .map(metadata -> StrategyTemplateResponse.builder()
                        .id(metadata.id())
                        .name(metadata.name())
                        .description(metadata.description())
                        .build())
                .toList();
    }

    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        validate(request);
        if (isAutomaticUniverseTemplate(request.getTemplateId()) && !StringUtils.hasText(request.getSymbol())) {
            request.setSymbol("FNO_UNIVERSE");
        }
        if (Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(request.getTemplateId().trim())) {
            Stock0925MoverAtrBreakoutStrategy.validateInvestment(request);
        }
        StrategyEvaluator evaluator = evaluators.get(normalizeTemplateId(request.getTemplateId()));
        if (evaluator == null) {
            throw new IllegalArgumentException("Unknown strategy template: " + request.getTemplateId());
        }
        request.setRiskSettings(org.com.sharekhan.strategy.run.StrategyRiskSettings.validate(request.getRiskSettings(),request.getLots()));
        java.util.function.Supplier<StrategyApplyResponse> work = () -> runCoordinator==null
                ? evaluator.apply(request) : runCoordinator.evaluate(request,()->evaluator.apply(request));
        var response = sharedDecisions == null ? work.get() : sharedDecisions.evaluate(work);
        if (response != null && response.getSharedSignalIds() != null && !response.getSharedSignalIds().isEmpty())
            log.info("STRATEGY_SHARED_SIGNALS | subscriptionId={} | userId={} | brokerCredentialsId={} | template={} | symbol={} | signalIds={}",
                    request.getSubscriptionId(), request.getUserId(), request.getBrokerCredentialsId(), request.getTemplateId(),
                    request.getSymbol(), response.getSharedSignalIds());
        if (tradeAudit != null && response != null && response.getTradeRequest() != null
                && response.getSharedSignalIds() != null && !response.getSharedSignalIds().isEmpty())
            tradeAudit.record(org.com.sharekhan.entity.TradeAuditEventEntity.builder()
                    .occurredAt(java.time.LocalDateTime.now(org.com.sharekhan.strategy.StrategySupport.MARKET_ZONE))
                    .appUserId(request.getUserId()).triggerRequestId(response.getTradeRequest().getId())
                    .strategyId(request.getTemplateId()).symbol(response.getSymbol()).source(response.getTradeRequest().getSource())
                    .strategyRunId(response.getTradeRequest().getStrategyRunId())
                    .eventType("SHARED_STRATEGY_DECISION").outcome(response.getStatus() == null ? "CREATED" : response.getStatus().toUpperCase(Locale.ROOT))
                    .reason("COMMON_MARKET_SIGNAL").details("sharedSignalIds=" + response.getSharedSignalIds()).build());
        return response;
    }

    private void validate(StrategyApplyRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!StringUtils.hasText(request.getTemplateId())) {
            throw new IllegalArgumentException("templateId is required");
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
    }

    private boolean isFnoMoverTemplate(String templateId) {
        return Fno0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(normalizeTemplateId(templateId));
    }

    private boolean isAutomaticUniverseTemplate(String templateId) {
        return Stock0925MoverAtrBreakoutStrategy.TEMPLATE_ID.equalsIgnoreCase(templateId == null ? "" : templateId.trim())
                || isFnoMoverTemplate(templateId)
                || MarketauxSentimentSwingAtrStrategy.TEMPLATE_ID.equalsIgnoreCase(normalizeTemplateId(templateId));
    }

    private String normalizeTemplateId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
