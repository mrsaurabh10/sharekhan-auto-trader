package org.com.sharekhan.service.broker;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
/** A durable explicit refusal, distinct from an order whose delivery is unknown. */
public interface RefusedExitRecovery {
    boolean hasDefinitivelyRefusedExit(TriggeredTradeSetupEntity trade,BrokerContext context);
}
