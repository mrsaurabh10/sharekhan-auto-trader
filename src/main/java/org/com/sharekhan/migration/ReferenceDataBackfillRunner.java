package org.com.sharekhan.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.reference-data.postgres.enabled:false}' == 'true' and '${app.reference-data.postgres.backfill-on-startup:false}' == 'true'")
public class ReferenceDataBackfillRunner implements ApplicationRunner {
    private static final int PAGE_SIZE = 500;

    private final ScriptMasterRepository scripts;
    private final MStockInstrumentRepository mstock;
    private final ShoonyaInstrumentRepository shoonya;

    @Qualifier("auditPostgresJdbcTemplate")
    private final NamedParameterJdbcTemplate pg;

    @Override
    public void run(ApplicationArguments args) {
        copyPages(scripts, "script_master",
                "scrip_code,trading_symbol,exchange,instrument_type,strike_price,expiry,lot_size,option_type",
                ":scripCode,:tradingSymbol,:exchange,:instrumentType,:strikePrice,:expiry,:lotSize,:optionType");
        copyPages(mstock, "mstock_instrument_master",
                "instrument_token,instrument_key,trading_symbol,name,exchange,segment,instrument_type,exchange_token,last_price,expiry,strike,tick_size,lot_size,fetched_at",
                ":instrumentToken,:instrumentKey,:tradingSymbol,:name,:exchange,:segment,:instrumentType,:exchangeToken,:lastPrice,:expiry,:strike,:tickSize,:lotSize,:fetchedAt");
        copyPages(shoonya, "shoonya_instrument_master",
                "id,instrument_key,exchange,token,trading_symbol,symbol,expiry,instrument,option_type,strike_price,lot_size,tick_size,fetched_at",
                ":id,:instrumentKey,:exchange,:token,:tradingSymbol,:symbol,:expiry,:instrument,:optionType,:strikePrice,:lotSize,:tickSize,:fetchedAt");
        syncShoonyaSequence();

        if (count("script_master") != scripts.count()
                || count("mstock_instrument_master") != mstock.count()
                || count("shoonya_instrument_master") != shoonya.count()) {
            throw new IllegalStateException("Reference data backfill count mismatch");
        }
        log.info("PostgreSQL reference-data backfill complete: scripts={} mstock={} shoonya={}",
                scripts.count(), mstock.count(), shoonya.count());
    }

    private <T, ID> void copyPages(PagingAndSortingRepository<T, ID> repository, String table, String columns, String values) {
        Pageable pageRequest = PageRequest.of(0, PAGE_SIZE);
        int pageNumber = 0;
        while (true) {
            Page<T> page = repository.findAll(pageRequest);
            copy(table, columns, values, page.getContent());
            pageNumber++;
            if (pageNumber % 20 == 0 || !page.hasNext()) {
                log.info("PostgreSQL reference-data progress: table={} pages={} copiedRows={}",
                        table, pageNumber, Math.min((long) pageNumber * PAGE_SIZE, page.getTotalElements()));
            }
            if (!page.hasNext()) {
                return;
            }
            pageRequest = page.nextPageable();
        }
    }

    private void copy(String table, String columns, String values, List<?> rows) {
        if (rows.isEmpty()) {
            return;
        }
        pg.batchUpdate("INSERT INTO " + table + " (" + columns + ") OVERRIDING SYSTEM VALUE VALUES (" + values + ") ON CONFLICT DO NOTHING",
                rows.stream().map(BeanPropertySqlParameterSource::new).toArray(SqlParameterSource[]::new));
    }

    private void syncShoonyaSequence() {
        pg.getJdbcTemplate().execute("SELECT setval(pg_get_serial_sequence('shoonya_instrument_master', 'id'), "
                + "COALESCE((SELECT MAX(id) FROM shoonya_instrument_master), 1), true)");
    }

    private long count(String table) {
        Long result = pg.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return result == null ? 0 : result;
    }
}
