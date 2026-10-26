package org.com.sharekhan.strategy.mcx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Flexible evaluation is opt-in for Simulator only; broker accounts retain the full session. */
@Component
public class NaturalGasDataPolicy {
    private final NaturalGasDataMode simulatorMode;
    private final NaturalGasCandleSource simulatorSource;
    private final NaturalGasTrendTimeframe simulatorTrendTimeframe;
    public NaturalGasDataPolicy(NaturalGasDataMode simulatorMode){this(simulatorMode,NaturalGasCandleSource.SHAREKHAN_LIVE);}
    public NaturalGasDataPolicy(NaturalGasDataMode simulatorMode,NaturalGasCandleSource simulatorSource){
        this(simulatorMode,simulatorSource,NaturalGasTrendTimeframe.FIFTEEN_MINUTE);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public NaturalGasDataPolicy(@Value("${app.strategy.naturalgas.simulator-data-mode:FULL_SESSION}") NaturalGasDataMode simulatorMode,
            @Value("${app.strategy.naturalgas.simulator-candle-source:SHAREKHAN_LIVE}") NaturalGasCandleSource simulatorSource,
            @Value("${app.strategy.naturalgas.simulator-trend-timeframe:FIFTEEN_MINUTE}") NaturalGasTrendTimeframe simulatorTrendTimeframe) {
        this.simulatorMode=java.util.Objects.requireNonNull(simulatorMode);
        this.simulatorSource=java.util.Objects.requireNonNull(simulatorSource);
        this.simulatorTrendTimeframe=java.util.Objects.requireNonNull(simulatorTrendTimeframe);
    }
    public NaturalGasDataMode forBroker(String brokerName) {
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorMode:NaturalGasDataMode.FULL_SESSION;
    }
    public NaturalGasDataMode simulatorMode(){return simulatorMode;}
    public NaturalGasCandleSource sourceForBroker(String brokerName){
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorSource:NaturalGasCandleSource.SHAREKHAN_LIVE;
    }
    public NaturalGasCandleSource simulatorSource(){return simulatorSource;}
    public NaturalGasTrendTimeframe trendForBroker(String brokerName){
        return "SIMULATOR".equalsIgnoreCase(brokerName)?simulatorTrendTimeframe:NaturalGasTrendTimeframe.FIFTEEN_MINUTE;
    }
    public NaturalGasTrendTimeframe simulatorTrendTimeframe(){return simulatorTrendTimeframe;}
}
