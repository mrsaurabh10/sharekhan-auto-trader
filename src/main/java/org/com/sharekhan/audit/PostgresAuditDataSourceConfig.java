package org.com.sharekhan.audit;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/** Separate PostgreSQL connection used exclusively by the audit-event migration POC. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${app.audit.postgres.enabled:false}' == 'true' or '${app.backtest.postgres.enabled:false}' == 'true' or '${app.user-broker.postgres.enabled:false}' == 'true' or '${app.trading-state.postgres.enabled:false}' == 'true' or '${app.configuration.postgres.enabled:false}' == 'true'")
public class PostgresAuditDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.audit.postgres")
    DataSourceProperties auditPostgresDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "auditPostgresDataSource")
    DataSource auditPostgresDataSource(
            @Qualifier("auditPostgresDataSourceProperties") DataSourceProperties auditPostgresDataSourceProperties) {
        return auditPostgresDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    NamedParameterJdbcTemplate auditPostgresJdbcTemplate(
            @Qualifier("auditPostgresDataSource") DataSource auditPostgresDataSource) {
        return new NamedParameterJdbcTemplate(auditPostgresDataSource);
    }

    @Bean(name = "auditPostgresTransactionManager")
    DataSourceTransactionManager auditPostgresTransactionManager(
            @Qualifier("auditPostgresDataSource") DataSource auditPostgresDataSource) {
        return new DataSourceTransactionManager(auditPostgresDataSource);
    }

    @Bean
    DataSourceInitializer auditPostgresSchemaInitializer(
            @Qualifier("auditPostgresDataSource") DataSource auditPostgresDataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/audit-event-schema.sql"),
                new ClassPathResource("db/postgresql/backtest-replay-schema.sql"),
                new ClassPathResource("db/postgresql/user-broker-schema.sql"),
                new ClassPathResource("db/postgresql/trading-state-schema.sql"),
                new ClassPathResource("db/postgresql/configuration-schema.sql"));
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(auditPostgresDataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
