package org.com.sharekhan.service;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.StrategyTemplateResponse;
import org.com.sharekhan.strategy.StrategyEvaluator;
import org.com.sharekhan.strategy.StrategyMetadata;
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
        if (!StringUtils.hasText(request.getSymbol())) {
            throw new IllegalArgumentException("symbol is required");
        }
    }

    private String normalizeTemplateId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
