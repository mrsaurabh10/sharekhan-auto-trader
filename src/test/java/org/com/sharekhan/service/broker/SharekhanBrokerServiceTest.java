package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharekhanBrokerServiceTest {

    @Test void modifiesTheChildWithSupportConfirmedBuyIntentAndParentPrices() {
        var context = new BrokerContext(73196L, "key", "CLIENT", "Sharekhan", 2L);
        var parent = new JSONObject().put("orderId","P1").put("customerId",73196).put("scripCode",15332)
                .put("tradingSymbol","NMDC").put("orderPrice","84.85").put("bookProfitPrice","85.55");
        var child = new JSONObject().put("orderId","C1").put("mpCoverOrderId","P1").put("childOrder",true)
                .put("customerId",73196).put("orderQty",3).put("rmsCode","SKNSE6");
        var payload = SharekhanBrokerService.bracketStopPayload(context,parent,child,84.85);
        assertThat(payload.getString("orderId")).isEqualTo("C1");
        assertThat(payload.getString("transactionType")).isEqualTo("B");
        assertThat(payload.getString("productType")).isEqualTo("BIGTRADEPLUS");
        assertThat(payload.getString("childSlPrice")).isEqualTo("84.85");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("85.55");
        assertThat(payload.getString("rmsCode")).isEqualTo("SKNSE6");
        assertThat(payload.getInt("triggerPrice")).isZero();
    }

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

    @Test
    void normalisesEveryBigTradePlusPriceToTheNseCashTick() {
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setScripCode(3518);
        trade.setSymbol("TORNTPHARM");
        trade.setExchange("NC");
        trade.setQuantity(3L);
        trade.setEntryPrice(4911.50d);
        trade.setStopLoss(4884.64d);
        trade.setTarget1(4931.19d);

        JSONObject payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(12345678L, "test-api-key", "CLIENT", "Sharekhan", 1L));

        assertThat(payload.getString("price")).isEqualTo("4911.50");
        assertThat(payload.getString("childSlPrice")).isEqualTo("4884.65");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("4931.20");
    }

    @Test
    void createsAStopLimitBigTradePlusParentInsideThePrearmWindow() {
        TriggeredTradeSetupEntity trade = new TriggeredTradeSetupEntity();
        trade.setScripCode(3045);
        trade.setSymbol("SBIN");
        trade.setExchange("NC");
        trade.setQuantity(10L);
        trade.setEntryPrice(1024d);
        trade.setTarget1(1025d);
        trade.setStopLoss(1010d);

        JSONObject payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(12345678L, "test-api-key", "CLIENT", "Sharekhan", 1L),
                1024d, 1024.05d);

        assertThat(payload.getString("orderType")).isEqualTo("BKT");
        assertThat(payload.getDouble("triggerPrice")).isEqualTo(1024d);
        assertThat(payload.getString("price")).isEqualTo("1024.05");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("1025.00");
        assertThat(payload.getString("childSlPrice")).isEqualTo("1010.00");
    }
}
