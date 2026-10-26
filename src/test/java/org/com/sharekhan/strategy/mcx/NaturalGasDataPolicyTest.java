package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasDataPolicyTest {
    @Test void fiveMinuteTrendIsExplicitAndSimulatorOnly() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(NaturalGasDataPolicy.class);
        runner.run(c->assertEquals(NaturalGasTrendTimeframe.FIFTEEN_MINUTE,c.getBean(NaturalGasDataPolicy.class).trendForBroker("Simulator")));
        runner.withPropertyValues("app.strategy.naturalgas.simulator-trend-timeframe=FIVE_MINUTE").run(c->{
            var policy=c.getBean(NaturalGasDataPolicy.class);
            assertEquals(NaturalGasTrendTimeframe.FIVE_MINUTE,policy.trendForBroker("Simulator"));
            for(String name:new String[]{"Sharekhan","Shoonya","MStock","",null})
                assertEquals(NaturalGasTrendTimeframe.FIFTEEN_MINUTE,policy.trendForBroker(name));
        });
        runner.withPropertyValues("app.strategy.naturalgas.simulator-trend-timeframe=UNKNOWN")
                .run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void flexibleModeIsRestrictedToSimulatorRegardlessOfOtherBrokerNames() {
        var policy=new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW);
        assertEquals(NaturalGasDataMode.ROLLING_WINDOW,policy.forBroker("Simulator"));
        for(String broker:new String[]{"Sharekhan","Dhan","MStock","",null})assertEquals(NaturalGasDataMode.FULL_SESSION,policy.forBroker(broker));
    }
    @Test void fullSessionModeRemainsAvailableForSimulator() {
        assertEquals(NaturalGasDataMode.FULL_SESSION,new NaturalGasDataPolicy(NaturalGasDataMode.FULL_SESSION).forBroker("Simulator"));
        assertThrows(NullPointerException.class,()->new NaturalGasDataPolicy(null));
    }
    @Test void configurationDefaultsToStrictAndExplicitlyBindsRollingMode() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(NaturalGasDataPolicy.class);
        runner.run(context->assertEquals(NaturalGasDataMode.FULL_SESSION,context.getBean(NaturalGasDataPolicy.class).simulatorMode()));
        runner.withPropertyValues("app.strategy.naturalgas.simulator-data-mode=ROLLING_WINDOW")
            .run(context->assertEquals(NaturalGasDataMode.ROLLING_WINDOW,context.getBean(NaturalGasDataPolicy.class).simulatorMode()));
        runner.withPropertyValues("app.strategy.naturalgas.simulator-data-mode=UNKNOWN")
            .run(context->assertNotNull(context.getStartupFailure()));
    }

    @Test void shoonyaSourceIsOptInAndCannotAffectLiveBrokerAccounts() {
        var p=new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW,NaturalGasCandleSource.SHOONYA_TPS);
        assertEquals(NaturalGasCandleSource.SHOONYA_TPS,p.sourceForBroker("Simulator"));
        assertEquals(NaturalGasCandleSource.SHAREKHAN_LIVE,p.sourceForBroker("Sharekhan"));
        assertEquals(NaturalGasCandleSource.SHAREKHAN_LIVE,p.sourceForBroker(null));
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(NaturalGasDataPolicy.class);
        runner.run(c->assertEquals(NaturalGasCandleSource.SHAREKHAN_LIVE,c.getBean(NaturalGasDataPolicy.class).simulatorSource()));
        runner.withPropertyValues("app.strategy.naturalgas.simulator-candle-source=SHOONYA_TPS").run(c->assertEquals(NaturalGasCandleSource.SHOONYA_TPS,c.getBean(NaturalGasDataPolicy.class).sourceForBroker("Simulator")));
    }

}
