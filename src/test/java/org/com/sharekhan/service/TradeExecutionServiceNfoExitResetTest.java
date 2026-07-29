package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TradeExecutionServiceNfoExitResetTest {

    private final TradeExecutionService service = new TradeExecutionService(
            null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    @Test
    void resetsNonIntradayNfoOptionTargetOrderAfterMarketCloseWithoutStatusLookup() {
        TriggeredTradeSetupEntity trade = nfoOptionTargetOrder();

        assertThat(service.isClosedNfoOptionExitOrder(trade,
                LocalDateTime.of(2026, 7, 29, 15, 30))).isTrue();
    }

    @Test
    void keepsNfoOptionTargetOrderProtectedBeforeMarketClose() {
        assertThat(service.isClosedNfoOptionExitOrder(nfoOptionTargetOrder(),
                LocalDateTime.of(2026, 7, 29, 15, 29, 59))).isFalse();
    }

    @Test
    void doesNotApplyExpiryAssumptionToEquityOrIntradayTrades() {
        TriggeredTradeSetupEntity equity = nfoOptionTargetOrder();
        equity.setExchange("NC");
        TriggeredTradeSetupEntity intraday = nfoOptionTargetOrder();
        intraday.setIntraday(true);
        LocalDateTime afterClose = LocalDateTime.of(2026, 7, 29, 17, 5);

        assertThat(service.isClosedNfoOptionExitOrder(equity, afterClose)).isFalse();
        assertThat(service.isClosedNfoOptionExitOrder(intraday, afterClose)).isFalse();
    }

    private TriggeredTradeSetupEntity nfoOptionTargetOrder() {
        return TriggeredTradeSetupEntity.builder()
                .exchange("NF")
                .optionType("CE")
                .intraday(false)
                .status(TriggeredTradeStatus.TARGET_ORDER_PLACED)
                .build();
    }
}
