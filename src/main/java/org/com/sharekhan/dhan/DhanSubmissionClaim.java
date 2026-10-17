package org.com.sharekhan.dhan;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class DhanSubmissionClaim {
    private final JdbcTemplate jdbc;
    /** Commits before network IO, independently of the caller's trading transaction. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void claim(String correlation, Long credential, String submissionKey, String side) {
        jdbc.update("INSERT INTO dhan_order_submissions (correlation_id, credential_id, submission_key, order_side, created_at) VALUES (?, ?, ?, ?, ?)",
            correlation, credential, submissionKey, side, Timestamp.from(Instant.now()));
    }
}
