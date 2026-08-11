package org.com.sharekhan.audit;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

/**
 * Keeps the configured application datasource as the primary datasource while the
 * PostgreSQL audit POC contributes a second datasource. Without this explicit
 * primary bean, Spring Boot sees the audit datasource and incorrectly assigns all
 * JPA repositories to PostgreSQL.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${app.audit.postgres.enabled:false}' == 'true' or '${app.backtest.postgres.enabled:false}' == 'true' or '${app.user-broker.postgres.enabled:false}' == 'true' or '${app.trading-state.postgres.enabled:false}' == 'true' or '${app.configuration.postgres.enabled:false}' == 'true' or '${app.reference-data.postgres.enabled:false}' == 'true'")
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

    /**
     * Retains the conventional transaction-manager name for all existing H2/JPA
     * repositories. The PostgreSQL migration manager is deliberately separately named.
     */
    @Bean(name = "transactionManager")
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
