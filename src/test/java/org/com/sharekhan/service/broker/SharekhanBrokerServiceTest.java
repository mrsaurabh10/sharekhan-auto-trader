package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharekhanBrokerServiceTest {

    @Test
    void createsTheDocumentedBigTradePlusBracketPayload() {
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setScripCode(3045);
        trade.setSymbol("SBIN");
        trade.setExchange("NC");
        trade.setQuantity(10L);
        trade.setEntryPrice(1024d);
        trade.setTarget1(1025d);
        trade.setStopLoss(1010d);

        JSONObject payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(12345678L, "test-api-key", "CLIENT", "Sharekhan", 1L));

        assertThat(payload.getString("orderType")).isEqualTo("BKT");
        assertThat(payload.getString("productType")).isEqualTo("BIGTRADEPLUS");
        assertThat(payload.getString("transactionType")).isEqualTo("B");
        assertThat(payload.getInt("triggerPrice")).isZero();
        assertThat(payload.getString("price")).isEqualTo("1024.00");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("1025.00");
        assertThat(payload.getString("childSlPrice")).isEqualTo("1010.00");
    }
}
