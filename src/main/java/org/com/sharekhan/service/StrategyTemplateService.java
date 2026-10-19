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

@Service
public class StrategyTemplateService {

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
        return evaluator.apply(request);
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
