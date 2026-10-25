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
}
