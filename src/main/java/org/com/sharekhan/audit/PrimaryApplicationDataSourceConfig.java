package org.com.sharekhan.audit;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Keeps the configured application datasource as the primary datasource while the
 * PostgreSQL audit POC contributes a second datasource. Without this explicit
 * primary bean, Spring Boot sees the audit datasource and incorrectly assigns all
 * JPA repositories to PostgreSQL.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${app.audit.postgres.enabled:false}' == 'true' or '${app.backtest.postgres.enabled:false}' == 'true'")
public class PrimaryApplicationDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties applicationDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "applicationDataSource")
    @Primary
    DataSource applicationDataSource(
            @Qualifier("applicationDataSourceProperties") DataSourceProperties applicationDataSourceProperties) {
        return applicationDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
