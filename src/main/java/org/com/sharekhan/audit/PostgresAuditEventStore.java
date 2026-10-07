package org.com.sharekhan.audit;

import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** PostgreSQL-backed implementation for the first incremental migration slice. */
@Repository
@DependsOn("auditPostgresSchemaInitializer")
@ConditionalOnProperty(prefix = "app.audit.postgres", name = "enabled", havingValue = "true")
public class PostgresAuditEventStore implements AuditEventStore {
    private static final String INSERT = """
            INSERT INTO trade_audit_events (
                occurred_at, app_user_id, trigger_request_id, trade_id, strategy_id, source, symbol,
                event_type, outcome, reason, option_type, expiry, strike_price, spot_price,
                option_ltp, best_bid, best_ask, details
            ) VALUES (
                :occurredAt, :appUserId, :triggerRequestId, :tradeId, :strategyId, :source, :symbol,
                :eventType, :outcome, :reason, :optionType, :expiry, :strikePrice, :spotPrice,
                :optionLtp, :bestBid, :bestAsk, :details
            ) RETURNING id
            """;

    private static final String INSERT_WITH_ID = """
            INSERT INTO trade_audit_events (
                id, occurred_at, app_user_id, trigger_request_id, trade_id, strategy_id, source, symbol,
                event_type, outcome, reason, option_type, expiry, strike_price, spot_price,
                option_ltp, best_bid, best_ask, details
            ) OVERRIDING SYSTEM VALUE VALUES (
                :id, :occurredAt, :appUserId, :triggerRequestId, :tradeId, :strategyId, :source, :symbol,
                :eventType, :outcome, :reason, :optionType, :expiry, :strikePrice, :spotPrice,
                :optionLtp, :bestBid, :bestAsk, :details
            ) ON CONFLICT (id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresAuditEventStore(
            @Qualifier("auditPostgresJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TradeAuditEventEntity save(TradeAuditEventEntity event) {
        Long id = jdbcTemplate.queryForObject(INSERT, parameters(event), Long.class);
        event.setId(id);
        return event;
    }

    @Override
    public List<TradeAuditEventEntity> findTop200() {
        return jdbcTemplate.query("SELECT * FROM trade_audit_events ORDER BY occurred_at DESC LIMIT 200", ROW_MAPPER);
    }

    @Override
    public List<TradeAuditEventEntity> findTop200ByAppUserId(Long appUserId) {
        return jdbcTemplate.query("SELECT * FROM trade_audit_events WHERE app_user_id = :appUserId "
                        + "ORDER BY occurred_at DESC LIMIT 200",
                new MapSqlParameterSource("appUserId", appUserId), ROW_MAPPER);
    }

    @Override
    public List<TradeAuditEventEntity> findByTriggerRequestId(Long triggerRequestId) {
        return jdbcTemplate.query("SELECT * FROM trade_audit_events WHERE trigger_request_id = :triggerRequestId "
                        + "ORDER BY occurred_at ASC",
                new MapSqlParameterSource("triggerRequestId", triggerRequestId), ROW_MAPPER);
    }

    @Override
    public List<TradeAuditEventEntity> findByTradeId(Long tradeId) {
        return jdbcTemplate.query("SELECT * FROM trade_audit_events WHERE trade_id = :tradeId ORDER BY occurred_at ASC",
                new MapSqlParameterSource("tradeId", tradeId), ROW_MAPPER);
    }

    int copyHistoricalEvents(List<TradeAuditEventEntity> events) {
        if (events.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = events.stream()
                .map(this::parametersWithId)
                .toArray(MapSqlParameterSource[]::new);
        int[] results = jdbcTemplate.batchUpdate(INSERT_WITH_ID, batch);
        return (int) java.util.Arrays.stream(results).filter(result -> result > 0).count();
    }

    long count() {
        Long count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM trade_audit_events", Long.class);
        return count == null ? 0 : count;
    }

    void synchronizeIdentitySequence() {
        jdbcTemplate.getJdbcTemplate().queryForObject("""
                SELECT setval(
                    pg_get_serial_sequence('trade_audit_events', 'id'),
                    COALESCE((SELECT MAX(id) FROM trade_audit_events), 1),
                    true
                )
                """, Long.class);
    }

    private MapSqlParameterSource parameters(TradeAuditEventEntity event) {
        return new MapSqlParameterSource()
                .addValue("occurredAt", event.getOccurredAt())
                .addValue("appUserId", event.getAppUserId())
                .addValue("triggerRequestId", event.getTriggerRequestId())
                .addValue("tradeId", event.getTradeId())
                .addValue("strategyId", event.getStrategyId())
                .addValue("source", event.getSource())
                .addValue("symbol", event.getSymbol())
                .addValue("eventType", event.getEventType())
                .addValue("outcome", event.getOutcome())
                .addValue("reason", event.getReason())
                .addValue("optionType", event.getOptionType())
                .addValue("expiry", event.getExpiry())
                .addValue("strikePrice", event.getStrikePrice())
                .addValue("spotPrice", event.getSpotPrice())
                .addValue("optionLtp", event.getOptionLtp())
                .addValue("bestBid", event.getBestBid())
                .addValue("bestAsk", event.getBestAsk())
                .addValue("details", event.getDetails());
    }

    private MapSqlParameterSource parametersWithId(TradeAuditEventEntity event) {
        return parameters(event).addValue("id", event.getId());
    }

    private static final RowMapper<TradeAuditEventEntity> ROW_MAPPER = new RowMapper<>() {
        @Override
        public TradeAuditEventEntity mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return TradeAuditEventEntity.builder()
                    .id(resultSet.getLong("id"))
                    .occurredAt(resultSet.getTimestamp("occurred_at").toLocalDateTime())
                    .appUserId(resultSet.getObject("app_user_id", Long.class))
                    .triggerRequestId(resultSet.getObject("trigger_request_id", Long.class))
                    .tradeId(resultSet.getObject("trade_id", Long.class))
                    .strategyId(resultSet.getString("strategy_id"))
                    .source(resultSet.getString("source"))
                    .symbol(resultSet.getString("symbol"))
                    .eventType(resultSet.getString("event_type"))
                    .outcome(resultSet.getString("outcome"))
                    .reason(resultSet.getString("reason"))
                    .optionType(resultSet.getString("option_type"))
                    .expiry(resultSet.getString("expiry"))
                    .strikePrice(resultSet.getObject("strike_price", Double.class))
                    .spotPrice(resultSet.getObject("spot_price", Double.class))
                    .optionLtp(resultSet.getObject("option_ltp", Double.class))
                    .bestBid(resultSet.getObject("best_bid", Double.class))
                    .bestAsk(resultSet.getObject("best_ask", Double.class))
                    .details(resultSet.getString("details"))
                    .build();
        }
    };
}
