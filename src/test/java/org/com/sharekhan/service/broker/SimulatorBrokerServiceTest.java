package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorBrokerServiceTest {
    @Test void indexSlDoesNotRejectPremiumAskAndThreeIndependentLotsFillAtActualBook() {
        var data=org.mockito.Mockito.mock(org.com.sharekhan.strategy.adxdmi.AdxDmiMarketData.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"adxQuotes",data);
        var q=org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot.builder().scripCode(42).bestAsk(226.6).bestBid(225.9)
                .askQuantity(195d).bidQuantity(195d).lastBookAt(java.time.Instant.now()).bookProviderIdentity("exact").build();
        org.mockito.Mockito.when(data.book(42,"NF")).thenReturn(q);
        org.mockito.Mockito.when(data.freshBook(org.mockito.ArgumentMatchers.eq("NF"),org.mockito.ArgumentMatchers.same(q),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(42))).thenReturn(true);
        var ids=new java.util.HashSet<String>();
        for(int leg=1;leg<=3;leg++) {
            var trade=TriggeredTradeSetupEntity.builder().source(org.com.sharekhan.strategy.adxdmi.AdxDmiRules.PREMIUM_SOURCE)
                    .scripCode(42).exchange("NF").optionType("CE").entryAction("BUY").entryPrice(226.6)
                    .useSpotForEntry(false).useSpotForSl(true).useSpotForTarget(true).stopLoss(25266.55).target1(25358.45)
                    .quantity(65L).build();
            var entry=service.placeOrder(trade,null,25312);
            ids.add(entry.getOrderId());assertThat(entry.getExecutedPrice()).isEqualTo(226.6);
            trade.setActualEntryPrice(entry.getExecutedPrice());
            var exit=service.placeExitOrder(trade,null,25358.45);
            assertThat(exit.getExecutedPrice()).isEqualTo(225.9);
            assertThat(exit.getPnl()).isCloseTo(-45.5,org.assertj.core.data.Offset.offset(.000001));
        }
        assertThat(ids).hasSize(3);
    }

    @Test void configuredQuoteFillsDoNotRequireDisplayedQuantitiesOrConsumeInventedDepth() {
        var data=org.mockito.Mockito.mock(org.com.sharekhan.strategy.adxdmi.AdxDmiMarketData.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"adxQuotes",data);
        var q=org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot.builder().scripCode(42).bestAsk(631.7).bestBid(630.15)
                .lastBookAt(java.time.Instant.now()).bookProviderIdentity("exact").build();
        org.mockito.Mockito.when(data.book(42,"BF")).thenReturn(q);
        org.mockito.Mockito.when(data.freshBook(org.mockito.ArgumentMatchers.eq("BF"),org.mockito.ArgumentMatchers.same(q),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(42))).thenReturn(true);
        var c=new org.com.sharekhan.execution.TradeExecutionSettings();c.setMarketableEntry(true);c.setRequireDisplayedDepth(false);
        var t=TriggeredTradeSetupEntity.builder().source(org.com.sharekhan.strategy.adxdmi.AdxDmiRules.PREMIUM_SOURCE)
                .scripCode(42).exchange("BF").optionType("PE").entryAction("BUY").entryPrice(630.8).actualEntryPrice(631.7)
                .useSpotForEntry(false).useSpotForSl(true).useSpotForTarget(true).stopLoss(81607.79).target1(81475.45).quantity(20L).executionSettings(c).build();
        var ids=new java.util.HashSet<String>();
        for(int leg=0;leg<3;leg++){
            var entry=service.placeOrder(t,null,643.4);assertThat(entry.getExecutedPrice()).isEqualTo(631.7);ids.add(entry.getOrderId());
            assertThat(service.placeExitOrder(t,null,630.15).getExecutedPrice()).isEqualTo(630.15);
        }
        assertThat(ids).hasSize(3);assertThat(t.getStopLoss()).isEqualTo(81607.79);
        org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(t,null,631.65));
        org.mockito.Mockito.when(data.freshBook(org.mockito.ArgumentMatchers.eq("BF"),org.mockito.ArgumentMatchers.same(q),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(42))).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(t,null,643.4));
    }

    private final SimulatorBrokerService service = new SimulatorBrokerService();

    @Test void shortExitBuysBackAndProfitsOnlyWhenPriceFalls() {
        var service = new SimulatorBrokerService();
        var trade = TriggeredTradeSetupEntity.builder().entryAction("SELL").entryPrice(100d).actualEntryPrice(100d).quantity(10L).build();
        assertThat(service.placeExitOrder(trade, null, 95d).getPnl()).isEqualTo(50d);
        assertThat(service.placeExitOrder(trade, null, 105d).getPnl()).isEqualTo(-50d);
    }

    @Test
    void supportsEntryModifyAsImmediateExecution() {
        TriggeredTradeSetupEntity trade = TriggeredTradeSetupEntity.builder()
                .symbol("NIFTY")
                .build();

        OrderPlacementResult result = service.modifyEntryOrder(trade, new BrokerContext(), "SIM-1", 123.45);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOrderId()).isEqualTo("SIM-1");
        assertThat(result.getStatus()).isEqualTo("Fully Executed");
        assertThat(result.getAttemptedPrice()).isEqualTo(123.45);
        assertThat(result.getExecutedPrice()).isEqualTo(123.45);
    }

    @Test void adxUsesFreshAskBidAndCannotReuseDisplayedDepthAcrossPartialExits(){
        var data=org.mockito.Mockito.mock(org.com.sharekhan.strategy.adxdmi.AdxDmiMarketData.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"adxQuotes",data);
        var q=org.com.sharekhan.cache.QuoteCacheService.QuoteSnapshot.builder().scripCode(42).bestAsk(100d).bestBid(99d)
                .askQuantity(195d).bidQuantity(65d).lastBookAt(java.time.Instant.now()).bookProviderIdentity("exact").build();
        org.mockito.Mockito.when(data.book(42,"BF")).thenReturn(q);
        org.mockito.Mockito.when(data.freshBook(org.mockito.ArgumentMatchers.eq("BF"),org.mockito.ArgumentMatchers.same(q),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(42))).thenReturn(true);
        var trade=TriggeredTradeSetupEntity.builder().source(org.com.sharekhan.strategy.adxdmi.AdxDmiRules.PREMIUM_SOURCE).scripCode(42).exchange("BF")
                .optionType("PE").entryAction("BUY").entryPrice(100.05).stopLoss(90d).quantity(195L).build();
        assertThat(service.placeOrder(trade,null,95).getExecutedPrice()).isEqualTo(100d);
        trade.setQuantity(65L);trade.setActualEntryPrice(100d);
        assertThat(service.placeExitOrder(trade,null,120).getExecutedPrice()).isEqualTo(99d);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.placeExitOrder(trade,null,120));
        trade.setEntryPrice(99d);org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(trade,null,101));
        trade.setEntryPrice(100.05);trade.setStopLoss(101d);org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(trade,null,100));
    }

    @Test void naturalGasBuysAtAskAndSellsPeAtBid() {
        var cache=new org.com.sharekhan.cache.QuoteCacheService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"quotes",cache);
        cache.recordQuote(42,4.9,5d,4.95,"SHAREKHAN_WS",null,"scrip:42",null,null);
        var trade=TriggeredTradeSetupEntity.builder().scripCode(42).symbol("NATURALGAS").optionType("PE").entryAction("BUY")
                .source(org.com.sharekhan.strategy.mcx.NaturalGasSession.SOURCE).actualEntryPrice(5d).entryPrice(5d).quantity(1250L).build();
        assertThat(service.placeOrder(trade,null,5d).getExecutedPrice()).isEqualTo(5d);
        var exit=service.placeExitOrder(trade,null,20d);assertThat(exit.getExecutedPrice()).isEqualTo(4.9);
        assertThat(exit.getPnl()).isCloseTo(-125d,org.assertj.core.data.Offset.offset(.00001));
    }
    @Test void naturalGasNeverFabricatesAnExitWithoutBookOrFillsAboveEntryLimit() {
        var cache=new org.com.sharekhan.cache.QuoteCacheService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"quotes",cache);
        var trade=TriggeredTradeSetupEntity.builder().scripCode(42).source(org.com.sharekhan.strategy.mcx.NaturalGasSession.SOURCE).build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,()->service.placeExitOrder(trade,null,5d));
        cache.recordQuote(42,5.9,6d,6d,"SHAREKHAN_WS",null,"scrip:42",null,null);
        org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(trade,null,5d));
    }

    @Test void averagingCannotFillAboveItsOriginallyAdmittedAskEvenIfGenericEntryLimitAllowsIt() {
        var cache=new org.com.sharekhan.cache.QuoteCacheService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"quotes",cache);
        cache.recordQuote(42,20d,20.05,20d,"SHAREKHAN_WS",null,"scrip:42",null,null);
        var trade=TriggeredTradeSetupEntity.builder().scripCode(42).source(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.SOURCE)
                .entryPrice(20d).quantity(1250L).optionType("PE").entryAction("BUY").build();
        org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(trade,null,20.1));
        cache.recordQuote(42,19.85,19.95,19.9,"SHAREKHAN_WS",null,"scrip:42",null,null);
        assertThat(service.placeOrder(trade,null,20d).getExecutedPrice()).isEqualTo(19.95);
    }
    @Test void averagingRechecksSpreadAtFillButWideSpreadDoesNotBlockExit() {
        var cache=new org.com.sharekhan.cache.QuoteCacheService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"quotes",cache);
        cache.recordQuote(42,19d,20d,20d,"SHAREKHAN_WS",null,"scrip:42",null,null);
        var trade=TriggeredTradeSetupEntity.builder().scripCode(42).source(org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy.SOURCE)
                .entryPrice(20d).actualEntryPrice(20d).quantity(1250L).optionType("CE").entryAction("BUY").build();
        org.junit.jupiter.api.Assertions.assertThrows(org.com.sharekhan.exception.EntryNotReadyException.class,()->service.placeOrder(trade,null,20d));
        assertThat(service.placeExitOrder(trade,null,19d).getExecutedPrice()).isEqualTo(19d);
    }

}
