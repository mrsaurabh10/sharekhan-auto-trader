package org.com.sharekhan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class SchedulerConfig implements SchedulingConfigurer {

    @Value("${app.scheduler.pool-size:8}")
    private int schedulerPoolSize;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(applicationTaskScheduler());
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler applicationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, schedulerPoolSize));
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}
