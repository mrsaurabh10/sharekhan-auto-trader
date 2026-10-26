package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalGasDataPolicyTest {
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

}
