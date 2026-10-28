package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorBrokerServiceTest {

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
