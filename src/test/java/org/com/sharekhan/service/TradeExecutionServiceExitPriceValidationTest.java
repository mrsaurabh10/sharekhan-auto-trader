package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.json.JSONArray;
import org.json.JSONObject;
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

    @Test
    void rejectsIndexSpotExitPriceForNonSpotOptionTrade() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(5209L)
                .symbol("NIFTY")
                .entryPrice(110.0)
                .actualEntryPrice(111.2)
                .optionType("PE")
                .useSpotForEntry(false)
                .useSpotForSl(false)
                .useSpotForTarget(false)
                .build();

        assertThat(service.hasUsableTradedExitPrice(trade, 23357.0)).isFalse();
    }

    @Test
    void acceptsOptionLikeExitPriceForNonSpotOptionTrade() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(5209L)
                .symbol("NIFTY")
                .entryPrice(110.0)
                .actualEntryPrice(111.2)
                .optionType("PE")
                .useSpotForEntry(false)
                .useSpotForSl(false)
                .useSpotForTarget(false)
                .build();

        assertThat(service.hasUsableTradedExitPrice(trade, 76.05)).isTrue();
    }

    @Test
    void rejectsPlaceholderBrokerOrderIds() {
        assertThat(service.isUsableBrokerOrderId(null)).isFalse();
        assertThat(service.isUsableBrokerOrderId("")).isFalse();
        assertThat(service.isUsableBrokerOrderId("0")).isFalse();
        assertThat(service.isUsableBrokerOrderId("NA")).isFalse();
        assertThat(service.isUsableBrokerOrderId("null")).isFalse();
    }

    @Test
    void acceptsRealBrokerOrderId() {
        assertThat(service.isUsableBrokerOrderId("182038823")).isTrue();
    }

    @Test
    void statusNormalizerRequiresFullyExecutedForFilledState() {
        assertThat(ShareKhanOrderUtil.isFullyExecutedStatus("Fully Executed")).isTrue();
        assertThat(ShareKhanOrderUtil.isFullyExecutedStatus("Partially Executed")).isFalse();
        assertThat(ShareKhanOrderUtil.isFullyExecutedStatus("Executed")).isFalse();
        assertThat(ShareKhanOrderUtil.isPartiallyExecutedStatus("Partially Executed")).isTrue();
    }

    @Test
    void treatsPartiallyExecutedEntryOrderHistoryAsPending() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(4471L)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .entryPrice(100.0)
                .stopLoss(90.0)
                .target1(110.0)
                .quantity(100L)
                .build();

        TradeExecutionService.TradeStatus status = service.evaluateOrderFinalStatus(
                trade,
                orderHistory("Partially Executed", "101.25")
        );

        assertThat(status).isEqualTo(TradeExecutionService.TradeStatus.PENDING);
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        assertThat(trade.getEntryAt()).isNull();
        assertThat(trade.getActualEntryPrice()).isNull();
        assertThat(trade.getEntryPrice()).isEqualTo(100.0);
    }

    @Test
    void treatsMStockCompleteEntryOrderAsExecutedAndCapturesAveragePrice() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(4472L)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .entryPrice(100.0)
                .stopLoss(90.0)
                .target1(110.0)
                .quantity(100L)
                .build();

        TradeExecutionService.TradeStatus status = service.evaluateOrderFinalStatus(
                trade,
                mStockOrderDetails("Complete", 101.25, 100, 100, 0)
        );

        assertThat(status).isEqualTo(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        assertThat(trade.getActualEntryPrice()).isEqualTo(101.25);
        assertThat(trade.getEntryPrice()).isEqualTo(101.25);
    }

    @Test
    void treatsMStockPartialQuantityAsPending() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(4473L)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .entryPrice(100.0)
                .quantity(100L)
                .build();

        TradeExecutionService.TradeStatus status = service.evaluateOrderFinalStatus(
                trade,
                mStockOrderDetails("Open", 101.25, 100, 40, 60)
        );

        assertThat(status).isEqualTo(TradeExecutionService.TradeStatus.PENDING);
        assertThat(trade.getActualEntryPrice()).isNull();
    }

    @Test
    void treatsMStockCompleteExitOrderAsExitedAndCalculatesPnl() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .id(4474L)
                .status(TriggeredTradeStatus.EXIT_ORDER_PLACED)
                .entryPrice(100.0)
                .actualEntryPrice(100.0)
                .quantity(10L)
                .build();

        TradeExecutionService.TradeStatus status = service.evaluateOrderFinalStatus(
                trade,
                mStockOrderDetails("Complete", 112.5, 10, 10, 0)
        );

        assertThat(status).isEqualTo(TradeExecutionService.TradeStatus.FULLY_EXECUTED);
        assertThat(trade.getExitPrice()).isEqualTo(112.5);
        assertThat(trade.getPnl()).isEqualTo(125.0);
    }

    private JSONObject orderHistory(String orderStatus, String avgPrice) {
        JSONObject row = new JSONObject()
                .put("orderStatus", orderStatus)
                .put("avgPrice", avgPrice);
        return new JSONObject().put("data", new JSONArray().put(row));
    }

    private JSONObject mStockOrderDetails(String status,
                                          double averagePrice,
                                          long quantity,
                                          long filledQuantity,
                                          long pendingQuantity) {
        JSONObject row = new JSONObject()
                .put("status", status)
                .put("average_price", averagePrice)
                .put("quantity", quantity)
                .put("filled_quantity", filledQuantity)
                .put("pending_quantity", pendingQuantity);
        return new JSONObject().put("data", new JSONArray().put(row));
    }
}
