package org.com.sharekhan.startup;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class H2SchemaCompatibilityInitializer {

    private static final int LAST_MESSAGE_LENGTH = 4096;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void widenLegacyColumns() {
        if (!isH2Database()) {
            return;
        }

        List<Integer> lengths = jdbcTemplate.queryForList("""
                SELECT CHARACTER_MAXIMUM_LENGTH
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = SCHEMA()
                  AND TABLE_NAME = 'STRATEGY_SUBSCRIPTIONS'
                  AND COLUMN_NAME = 'LAST_MESSAGE'
                """, Integer.class);

        if (lengths.isEmpty() || lengths.get(0) == null || lengths.get(0) >= LAST_MESSAGE_LENGTH) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE STRATEGY_SUBSCRIPTIONS ALTER COLUMN LAST_MESSAGE VARCHAR(" + LAST_MESSAGE_LENGTH + ")");
        log.info("Widened STRATEGY_SUBSCRIPTIONS.LAST_MESSAGE to VARCHAR({})", LAST_MESSAGE_LENGTH);
    }

    private boolean isH2Database() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName() != null
                    && metaData.getDatabaseProductName().toLowerCase().contains("h2");
        } catch (SQLException e) {
            log.warn("Unable to inspect database metadata for schema compatibility checks: {}", e.getMessage());
            return false;
        }
    }
}
