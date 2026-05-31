package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;

public interface StrategyEvaluator {
    StrategyMetadata metadata();

    StrategyApplyResponse apply(StrategyApplyRequest request);
}
