package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.json.JSONObject;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharekhanBrokerServiceTest {

    @Test void createsCashSellBracketBelowEntry() {
        var trade = TriggeredTradeSetupEntity.builder().source("spot-atr-pdl-bigtradeplus")
                .symbol("SBIN").scripCode(3045).exchange("NC").quantity(9L).intraday(true)
                .entryPrice(100d).stopLoss(102d).target1(97d).build();
        var payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(73196L,"key","CLIENT","Sharekhan",2L),100d,99.95, new BigDecimal("0.05"));
        assertThat(payload.getString("transactionType")).isEqualTo("S");
        assertThat(payload.getString("price")).isEqualTo("99.95");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("97.00");
        assertThat(payload.getString("childSlPrice")).isEqualTo("102.00");
    }

    @Test void modifiesTheChildWithSupportConfirmedBuyIntentAndParentPrices() {
        var context = new BrokerContext(73196L, "key", "CLIENT", "Sharekhan", 2L);
        var parent = new JSONObject().put("orderId","P1").put("customerId",73196).put("scripCode",15332)
                .put("tradingSymbol","NMDC").put("orderPrice","84.85").put("bookProfitPrice","85.55");
        var child = new JSONObject().put("orderId","C1").put("mpCoverOrderId","P1").put("childOrder",true)
                .put("customerId",73196).put("orderQty",3).put("rmsCode","SKNSE6");
        var payload = SharekhanBrokerService.bracketStopPayload(context,parent,child,84.85, new BigDecimal("0.01"));
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
                new BrokerContext(12345678L, "test-api-key", "CLIENT", "Sharekhan", 1L), new BigDecimal("0.10"));

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
                new BrokerContext(12345678L, "test-api-key", "CLIENT", "Sharekhan", 1L), new BigDecimal("0.10"));

        assertThat(payload.getString("price")).isEqualTo("4911.50");
        assertThat(payload.getString("childSlPrice")).isEqualTo("4884.60");
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
                1024d, 1024.05d, new BigDecimal("0.10"));

        assertThat(payload.getString("orderType")).isEqualTo("BKT");
        assertThat(payload.getDouble("triggerPrice")).isEqualTo(1024d);
        assertThat(payload.getString("price")).isEqualTo("1024.10");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("1025.00");
        assertThat(payload.getString("childSlPrice")).isEqualTo("1010.00");
    }
    @Test
    void maxhealthUsesTenPaiseTicksAndKeepsLimitBeyondTrigger() {
        var trade = TriggeredTradeSetupEntity.builder().symbol("MAXHEALTH").scripCode(22377)
                .exchange("NC").quantity(4L).entryPrice(1038.74).stopLoss(1028.73).target1(1053.26).build();
        var payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(1L, "key", "CLIENT", "Sharekhan", 2L),
                1038.74, 1038.80, new BigDecimal("0.10"));
        assertThat(payload.getDouble("triggerPrice")).isEqualTo(1038.80);
        assertThat(payload.getString("price")).isEqualTo("1038.90");
        assertThat(payload.getString("childSlPrice")).isEqualTo("1028.70");
        assertThat(payload.getString("bookProfitPrice")).isEqualTo("1053.30");
    }

    @Test
    void cashShortRoundsTriggerDownAndKeepsLimitOneInstrumentTickBelow() {
        var trade = TriggeredTradeSetupEntity.builder().symbol("MAXHEALTH").scripCode(22377)
                .source("spot-atr-pdl-bigtradeplus").exchange("NC").quantity(4L)
                .entryPrice(1038.74).stopLoss(1053.26).target1(1028.73).build();
        var payload = SharekhanBrokerService.bigTradePlusPayload(trade,
                new BrokerContext(1L, "key", "CLIENT", "Sharekhan", 2L),
                1038.74, 1038.70, new BigDecimal("0.10"));
        assertThat(payload.getDouble("triggerPrice")).isEqualTo(1038.70);
        assertThat(payload.getString("price")).isEqualTo("1038.60");
    }

    @Test
    void refusesAnUnknownInstrumentTickBeforeCallingBroker() {
        var repository = org.mockito.Mockito.mock(org.com.sharekhan.repository.ScriptMasterRepository.class);
        var tokens = org.mockito.Mockito.mock(org.com.sharekhan.auth.TokenStoreService.class);
        var service = new SharekhanBrokerService(tokens, repository);
        var trade = TriggeredTradeSetupEntity.builder().symbol("MAXHEALTH").scripCode(22377).exchange("NC").brokerProductType("BIGTRADEPLUS")
                .entryPrice(1038.74).build();
        var result = service.placeTriggerPriceEntryOrder(trade,
                new BrokerContext(1L, "key", "CLIENT", "Sharekhan", 2L), 1038.74, 1038.80);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getRejectionReason()).contains("BTP_INVALID_TICK_SIZE");
        org.mockito.Mockito.verifyNoInteractions(tokens);
    }
    @Test
    void resolvesMaxhealthTickFromInstrumentMasterAndStoresAlignedPrices() {
        var repository = org.mockito.Mockito.mock(org.com.sharekhan.repository.ScriptMasterRepository.class);
        var tokens = org.mockito.Mockito.mock(org.com.sharekhan.auth.TokenStoreService.class);
        org.mockito.Mockito.when(repository.findById(22377))
                .thenReturn(java.util.Optional.of(org.com.sharekhan.entity.ScriptMasterEntity.builder().exchange("NC").tickSize(0.10).build()));
        var service = new SharekhanBrokerService(tokens, repository);
        var trade = TriggeredTradeSetupEntity.builder().symbol("MAXHEALTH").scripCode(22377).exchange("NC")
                .quantity(4L).brokerProductType("BIGTRADEPLUS")
                .entryPrice(1038.74).stopLoss(1028.73).target1(1053.26).build();
        var result = service.placeTriggerPriceEntryOrder(trade,
                new BrokerContext(1L, "key", "CLIENT", "Sharekhan", 2L), 1038.74, 1038.80);
        // Missing token stops this unit test before any network call.
        assertThat(result.getRejectionReason()).isEqualTo("Sharekhan access token is unavailable");
        assertThat(trade.getEntryPrice()).isEqualTo(1038.80);
        assertThat(trade.getStopLoss()).isEqualTo(1028.70);
        assertThat(trade.getTarget1()).isEqualTo(1053.30);
    }
    @Test
    void regularEntriesExitsAndModificationsUseStoredSharekhanTick() throws Throwable {
        var repository = org.mockito.Mockito.mock(org.com.sharekhan.repository.ScriptMasterRepository.class);
        var tokens = org.mockito.Mockito.mock(org.com.sharekhan.auth.TokenStoreService.class);
        var sdk = org.mockito.Mockito.mock(com.sharekhan.SharekhanConnect.class);
        var context = new BrokerContext(1L, "key", "CLIENT", "Sharekhan", 2L);
        var trade = TriggeredTradeSetupEntity.builder().symbol("MAXHEALTH").scripCode(22377).exchange("NC")
                .quantity(4L).entryPrice(1038.74).build();
        org.mockito.Mockito.when(repository.findById(22377)).thenReturn(java.util.Optional.of(
                org.com.sharekhan.entity.ScriptMasterEntity.builder().exchange("NC").tickSize(0.1).build()));
        org.mockito.Mockito.when(sdk.placeOrder(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JSONObject().put("data", new JSONObject().put("orderId", "ORDER-1")));
        var service = org.mockito.Mockito.spy(new SharekhanBrokerService(tokens, repository));
        org.mockito.Mockito.doReturn(sdk).when(service).createClient("key", null);
        org.mockito.Mockito.when(sdk.modifyorder(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JSONObject().put("data", new JSONObject().put("orderId", "ORDER-1")));
        assertThat(service.placeTriggerPriceEntryOrder(trade, context, 1038.74, 1038.75).isSuccess()).isTrue();
        assertThat(service.placeExitOrder(trade, context, 1038.75).isSuccess()).isTrue();
        assertThat(service.modifyEntryOrder(trade, context, "ORDER-1", 1038.75).getAttemptedPrice()).isEqualTo(1038.8);
        var captor = org.mockito.ArgumentCaptor.forClass(com.sharekhan.model.OrderParams.class);
        org.mockito.Mockito.verify(sdk, org.mockito.Mockito.times(2)).placeOrder(captor.capture());
        assertThat(captor.getAllValues().get(0).triggerPrice).isEqualTo("1038.8");
        assertThat(captor.getAllValues().get(0).price).isEqualTo("1038.8");
        assertThat(captor.getAllValues().get(1).price).isEqualTo("1038.8");
        org.mockito.Mockito.verify(sdk).modifyorder(captor.capture());
        assertThat(captor.getValue().price).isEqualTo("1038.8");
    }

    @Test
    void refusesTickFromDifferentExchangeEvenWithSameScripCode() {
        var repository = org.mockito.Mockito.mock(org.com.sharekhan.repository.ScriptMasterRepository.class);
        org.mockito.Mockito.when(repository.findById(22377)).thenReturn(java.util.Optional.of(
                org.com.sharekhan.entity.ScriptMasterEntity.builder().exchange("BC").tickSize(0.05).build()));
        var service = new SharekhanBrokerService(null, repository);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.instrumentTickSize(22377, "NC"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
