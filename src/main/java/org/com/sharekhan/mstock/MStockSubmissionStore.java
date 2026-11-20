package org.com.sharekhan.mstock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class MStockSubmissionStore {
    private final JdbcTemplate jdbc;
    public record Attempt(String tag, int generation, String orderId, String outcome) {}

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Attempt> latest(Long credential, String key) {
        return jdbc.query("SELECT tag,generation,order_id,outcome FROM mstock_order_submissions "
                + "WHERE credential_id=? AND submission_key=? ORDER BY generation DESC LIMIT 1",
            (rs, n) -> new Attempt(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)), credential, key)
            .stream().findFirst();
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(String tag, Long credential, String key, int generation) {
        jdbc.update("INSERT INTO mstock_order_submissions (tag,credential_id,submission_key,generation,created_at) VALUES (?,?,?,?,?)",
            tag, credential, key, generation, Timestamp.from(Instant.now()));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accepted(String tag, String orderId) {
        if (jdbc.update("UPDATE mstock_order_submissions SET order_id=? WHERE tag=?", orderId, tag) != 1)
            throw new IllegalStateException("Missing mStock submission");
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inactive(String tag, String outcome) {
        if (!java.util.Set.of("REFUSED", "INACTIVE_ZERO_FILL").contains(outcome)) throw new IllegalArgumentException();
        if (jdbc.update("UPDATE mstock_order_submissions SET outcome=? WHERE tag=?", outcome, tag) != 1)
            throw new IllegalStateException("Missing mStock submission");
    }
}
