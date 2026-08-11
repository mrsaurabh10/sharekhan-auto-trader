package org.com.sharekhan.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** One-time idempotent H2 to PostgreSQL copy; H2 continues serving all live access. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.user-broker.postgres.enabled:false}' == 'true' and '${app.user-broker.postgres.backfill-on-startup:false}' == 'true'")
public class UserBrokerBackfillRunner implements ApplicationRunner {
    private static final int PAGE_SIZE = 500;

    private final AppUserRepository h2Users;
    private final BrokerCredentialsRepository h2Credentials;
    private final PostgresUserBrokerStore postgres;

    @Override
    public void run(ApplicationArguments args) {
        copyUsers();
        copyBrokerCredentials();
        postgres.synchronizeIdentitySequences();

        long h2UserCount = h2Users.count();
        long h2CredentialCount = h2Credentials.count();
        long postgresUserCount = postgres.countUsers();
        long postgresCredentialCount = postgres.countBrokerCredentials();
        if (postgresUserCount != h2UserCount || postgresCredentialCount != h2CredentialCount) {
            throw new IllegalStateException("User/broker backfill count mismatch: H2 users/credentials="
                    + h2UserCount + "/" + h2CredentialCount + ", PostgreSQL="
                    + postgresUserCount + "/" + postgresCredentialCount);
        }
        log.info("PostgreSQL user/broker backfill complete: users={} brokerCredentials={}",
                postgresUserCount, postgresCredentialCount);
    }

    private void copyUsers() {
        copyPages(h2Users, postgres::copyUsers);
    }

    private void copyBrokerCredentials() {
        copyPages(h2Credentials, postgres::copyBrokerCredentials);
    }

    private <T> void copyPages(org.springframework.data.repository.PagingAndSortingRepository<T, Long> repository,
                               java.util.function.Consumer<List<T>> copy) {
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id"));
        Page<T> page;
        do {
            page = repository.findAll(pageable);
            copy.accept(page.getContent());
            pageable = page.nextPageable();
        } while (page.hasNext());
    }
}
