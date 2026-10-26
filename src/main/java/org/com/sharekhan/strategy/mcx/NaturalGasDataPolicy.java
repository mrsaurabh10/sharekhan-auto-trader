package org.com.sharekhan.strategy.mcx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Flexible evaluation is opt-in for Simulator only; broker accounts retain the full session. */
@Component
public class NaturalGasDataPolicy {
    private final NaturalGasDataMode simulatorMode;
    private final NaturalGasCandleSource simulatorSource;
    public NaturalGasDataPolicy(NaturalGasDataMode simulatorMode){this(simulatorMode,NaturalGasCandleSource.SHAREKHAN_LIVE);}
    @org.springframework.beans.factory.annotation.Autowired
    public NaturalGasDataPolicy(@Value("${app.strategy.naturalgas.simulator-data-mode:FULL_SESSION}") NaturalGasDataMode simulatorMode,
            @Value("${app.strategy.naturalgas.simulator-candle-source:SHAREKHAN_LIVE}") NaturalGasCandleSource simulatorSource) {
        this.simulatorMode=java.util.Objects.requireNonNull(simulatorMode);
        this.simulatorSource=java.util.Objects.requireNonNull(simulatorSource);
    }
    public NaturalGasDataMode forBroker(String brokerName) {
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorMode:NaturalGasDataMode.FULL_SESSION;
    }
    public NaturalGasDataMode simulatorMode(){return simulatorMode;}
    public NaturalGasCandleSource sourceForBroker(String brokerName){
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorSource:NaturalGasCandleSource.SHAREKHAN_LIVE;
    }
    public NaturalGasCandleSource simulatorSource(){return simulatorSource;}
}
