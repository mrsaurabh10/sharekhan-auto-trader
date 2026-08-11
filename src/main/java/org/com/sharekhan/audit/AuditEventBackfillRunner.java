package org.com.sharekhan.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.repository.TradeAuditEventRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent backfill of the first PostgreSQL migration slice.
 * It is deliberately disabled unless the startup property is set explicitly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnBean(PostgresAuditEventStore.class)
@ConditionalOnProperty(prefix = "app.audit.postgres", name = "backfill-on-startup", havingValue = "true")
public class AuditEventBackfillRunner implements ApplicationRunner {
    private static final int PAGE_SIZE = 500;

    private final TradeAuditEventRepository h2Repository;
    private final PostgresAuditEventStore postgresStore;

    @Override
    public void run(ApplicationArguments args) {
        long expectedRows = h2Repository.count();
        long copiedRows = 0;
        Pageable pageRequest = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));

        Page<TradeAuditEventEntity> page;
        do {
            page = h2Repository.findAll(pageRequest);
            copiedRows += postgresStore.copyHistoricalEvents(page.getContent());
            pageRequest = page.nextPageable();
        } while (page.hasNext());

        postgresStore.synchronizeIdentitySequence();
        long postgresRows = postgresStore.count();
        if (postgresRows < expectedRows) {
            throw new IllegalStateException("Audit backfill incomplete: H2=" + expectedRows
                    + ", PostgreSQL=" + postgresRows);
        }
        log.info("PostgreSQL audit backfill complete: H2 rows={}, inserted this run={}, PostgreSQL rows={}",
                expectedRows, copiedRows, postgresRows);
    }
}
