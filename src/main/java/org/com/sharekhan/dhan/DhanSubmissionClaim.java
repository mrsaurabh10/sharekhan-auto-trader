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

    public record ExitAttempt(String correlation, String key, int generation, String outcome) {}

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public java.util.Optional<ExitAttempt> latestExit(Long credential, Long trade) {
        return latestExit(credential, trade, "SELL");
    }

    @Transactional(readOnly=true, propagation=Propagation.REQUIRES_NEW)
    public java.util.Optional<ExitAttempt> latestExit(Long credential, Long trade, String side) {
        validateSide(side);
        String base = "TRADE:" + trade;
        return jdbc.query("SELECT correlation_id,submission_key,COALESCE(generation,0),outcome FROM dhan_order_submissions "
                + "WHERE credential_id=? AND order_side=? AND (submission_key=? OR submission_key LIKE ?) "
                + "ORDER BY COALESCE(generation,0) DESC,created_at DESC LIMIT 1",
                (rs,n)->new ExitAttempt(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4)),
                credential,side,base,base+":EXIT:%").stream().findFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void claimExit(String correlation, Long credential, String key, int generation) {
        claimExit(correlation, credential, key, generation, "SELL");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void claimExit(String correlation, Long credential, String key, int generation, String side) {
        validateSide(side);
        jdbc.update("INSERT INTO dhan_order_submissions (correlation_id,credential_id,submission_key,order_side,created_at,generation) VALUES (?,?,?,?,?,?)",
                correlation,credential,key,side,Timestamp.from(Instant.now()),generation);
    }

    /** Only an explicit HTTP refusal or a validated terminal order with zero fills may advance a generation. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void exitOutcome(String correlation, String outcome) {
        exitOutcome(correlation, outcome, "SELL");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void exitOutcome(String correlation, String outcome, String side) {
        validateSide(side);
        if (!java.util.Set.of("REFUSED","INACTIVE_ZERO_FILL").contains(outcome)) throw new IllegalArgumentException();
        if (jdbc.update("UPDATE dhan_order_submissions SET outcome=? WHERE correlation_id=? AND order_side=?",outcome,correlation,side)!=1)
            throw new IllegalStateException("Missing exit submission");
    }
    private static void validateSide(String side) {
        if (!java.util.Set.of("BUY", "SELL").contains(side)) throw new IllegalArgumentException("Invalid exit side");
    }
}
