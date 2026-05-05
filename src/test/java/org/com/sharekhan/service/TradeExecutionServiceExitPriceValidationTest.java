package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeExecutionServiceExitPriceValidationTest {

    private final TradeExecutionService service = new TradeExecutionService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    @Test
    void rejectsSpotLikeExitPriceForSpotBasedOptionTrade() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(3306L)
                .symbol("TECHM")
                .entryPrice(1490.55)
                .actualEntryPrice(31.5)
                .useSpotForEntry(true)
                .useSpotForSl(true)
                .useSpotForTarget(true)
                .build();

        assertThat(service.hasUsableTradedExitPrice(trade, 1487.2)).isFalse();
    }

    @Test
    void rejectsSpotLikeEntryPriceForSpotBasedOptionRequest() {
        TriggerTradeRequestEntity request = TriggerTradeRequestEntity.builder()
                .id(3332L)
                .symbol("MFSL")
                .entryPrice(1680.0)
                .useSpotForEntry(true)
                .useSpotForSl(true)
                .useSpotForTarget(true)
                .build();

        assertThat(service.hasUsableTradedEntryPrice(request, 1675.6)).isFalse();
    }

    @Test
    void rejectsExitWhenPersistedActualEntryPriceWasSpotLike() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(3332L)
                .symbol("MFSL")
                .entryPrice(1680.0)
                .actualEntryPrice(1675.6)
                .useSpotForEntry(true)
                .useSpotForSl(true)
                .useSpotForTarget(true)
                .build();

        assertThat(service.hasUsableTradedExitPrice(trade, 39.0)).isFalse();
    }

    @Test
    void acceptsOptionLikeExitPriceForSpotBasedOptionTrade() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(3914L)
                .symbol("TECHM")
                .entryPrice(1465.0)
                .actualEntryPrice(36.4)
                .useSpotForEntry(true)
                .useSpotForSl(true)
                .useSpotForTarget(true)
                .build();

        assertThat(service.hasUsableTradedExitPrice(trade, 34.2)).isTrue();
    }
}
