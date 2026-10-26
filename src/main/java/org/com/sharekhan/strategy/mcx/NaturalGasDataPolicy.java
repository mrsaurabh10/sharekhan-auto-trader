package org.com.sharekhan.strategy.mcx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Flexible evaluation is opt-in for Simulator only; broker accounts retain the full session. */
@Component
public class NaturalGasDataPolicy {
    private final NaturalGasDataMode simulatorMode;
    public NaturalGasDataPolicy(@Value("${app.strategy.naturalgas.simulator-data-mode:FULL_SESSION}") NaturalGasDataMode simulatorMode) {
        this.simulatorMode=java.util.Objects.requireNonNull(simulatorMode);
    }
    public NaturalGasDataMode forBroker(String brokerName) {
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorMode:NaturalGasDataMode.FULL_SESSION;
    }
    public NaturalGasDataMode simulatorMode(){return simulatorMode;}
}
