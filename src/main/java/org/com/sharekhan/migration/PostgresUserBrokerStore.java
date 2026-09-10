package org.com.sharekhan.migration;

import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.List;

/** PostgreSQL target used only by the controlled user/broker migration. */
@Repository
@ConditionalOnProperty(prefix = "app.user-broker.postgres", name = "enabled", havingValue = "true")
public class PostgresUserBrokerStore {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresUserBrokerStore(@Qualifier("auditPostgresJdbcTemplate") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void copyUsers(List<AppUser> users) {
        if (users.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO app_user (id, username, password, customer_id, notes)
                OVERRIDING SYSTEM VALUE
                VALUES (:id, :username, :password, :customerId, :notes)
                ON CONFLICT (id) DO UPDATE SET
                    username = EXCLUDED.username,
                    password = EXCLUDED.password,
                    customer_id = EXCLUDED.customer_id,
                    notes = EXCLUDED.notes
                """, users.stream().map(BeanPropertySqlParameterSource::new).toArray(SqlParameterSource[]::new));
    }

    public void copyBrokerCredentials(List<BrokerCredentialsEntity> credentials) {
        if (credentials.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO broker_credentials (
                    id, broker_name, customer_id, app_user_id, api_key, broker_username,
                    broker_password, client_code, totp_secret, secret_key, active,
                    trading_enabled, default_for_orders)
                OVERRIDING SYSTEM VALUE
                VALUES (
                    :id, :brokerName, :customerId, :appUserId, :apiKey, :brokerUsername,
                    :brokerPassword, :clientCode, :totpSecret, :secretKey, :active,
                    :tradingEnabled, :defaultForOrders)
                ON CONFLICT (id) DO UPDATE SET
                    broker_name = EXCLUDED.broker_name,
                    customer_id = EXCLUDED.customer_id,
                    app_user_id = EXCLUDED.app_user_id,
                    api_key = EXCLUDED.api_key,
                    broker_username = EXCLUDED.broker_username,
                    broker_password = EXCLUDED.broker_password,
                    client_code = EXCLUDED.client_code,
                    totp_secret = EXCLUDED.totp_secret,
                    secret_key = EXCLUDED.secret_key,
                    active = EXCLUDED.active,
                    trading_enabled = EXCLUDED.trading_enabled,
                    default_for_orders = EXCLUDED.default_for_orders
                """, credentials.stream().map(BeanPropertySqlParameterSource::new).toArray(SqlParameterSource[]::new));
    }

    public long countUsers() {
        return count("app_user");
    }

    public long countBrokerCredentials() {
        return count("broker_credentials");
    }

    public void synchronizeIdentitySequences() {
        jdbc.getJdbcTemplate().execute("SELECT setval(pg_get_serial_sequence('app_user', 'id'), COALESCE((SELECT MAX(id) FROM app_user), 1), true)");
        jdbc.getJdbcTemplate().execute("SELECT setval(pg_get_serial_sequence('broker_credentials', 'id'), COALESCE((SELECT MAX(id) FROM broker_credentials), 1), true)");
    }

    private long count(String table) {
        Long count = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
