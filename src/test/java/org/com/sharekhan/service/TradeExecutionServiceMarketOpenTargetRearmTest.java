package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeExecutionServiceMarketOpenTargetRearmTest {

    private final TradeExecutionService service = new TradeExecutionService(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    @Test
    void rearmsResetNonIntradayNfoOptionWithOptionPriceTarget() {
        TriggeredTradeSetupEntity trade = optionTrade();

        assertThat(service.isEligibleForMarketOpenOptionTargetRearm(trade)).isTrue();
    }

    @Test
    void doesNotRearmSpotTargetOrTradeThatAlreadyHasExitOrder() {
        TriggeredTradeSetupEntity spotTarget = optionTrade();
        spotTarget.setUseSpotForTarget(true);
        TriggeredTradeSetupEntity alreadyPlaced = optionTrade();
        alreadyPlaced.setExitOrderId("196400770");

        assertThat(service.isEligibleForMarketOpenOptionTargetRearm(spotTarget)).isFalse();
        assertThat(service.isEligibleForMarketOpenOptionTargetRearm(alreadyPlaced)).isFalse();
    }

    private TriggeredTradeSetupEntity optionTrade() {
        return TriggeredTradeSetupEntity.builder()
                .id(7307L)
                .exchange("NF")
                .optionType("CE")
                .intraday(false)
                .status(TriggeredTradeStatus.EXECUTED)
                .target1(200d)
                .build();
    }
}
