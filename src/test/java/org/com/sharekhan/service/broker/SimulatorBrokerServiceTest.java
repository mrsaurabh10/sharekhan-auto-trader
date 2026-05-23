package org.com.sharekhan.service.broker;

import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorBrokerServiceTest {

    private final SimulatorBrokerService service = new SimulatorBrokerService();

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
