package org.com.sharekhan.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class SupertrendStrategyPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties(SupertrendStrategyProperties.class)
    static class Config { }
    @Test void bindsOverridesBeforeValidation() {
        runner.withPropertyValues("app.strategy.supertrend.ce-rsi-min=45", "app.strategy.supertrend.ce-rsi-max=65", "app.strategy.supertrend.require-rsi-direction=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var props=context.getBean(SupertrendStrategyProperties.class);
                    assertThat(props.getCeRsiMin()).isEqualTo(45);
                    assertThat(props.getCeRsiMax()).isEqualTo(65);
                    assertThat(props.isRequireRsiDirection()).isTrue();
                });
    }
    @Test void rejectsInvalidRangesAndNonfiniteThresholds() {
        for (String setting : new String[]{"ce-rsi-min=80", "pe-rsi-min=-1", "default-adx-threshold=NaN", "banknifty-adx-threshold=101"})
            runner.withPropertyValues("app.strategy.supertrend."+setting).run(context -> assertThat(context).hasFailed());
    }
}
