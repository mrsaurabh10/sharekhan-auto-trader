package org.com.sharekhan.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Committed before sending an order; broker tags are reconciliation keys, not idempotency keys. */
@Entity @Table(name = "mstock_order_submissions", uniqueConstraints =
    @UniqueConstraint(columnNames = {"credential_id", "submission_key", "generation"}))
public class MStockOrderSubmission {
    @Id @Column(length = 24) private String tag;
    @Column(name = "credential_id", nullable = false) private Long credentialId;
    @Column(name = "submission_key", nullable = false, length = 100) private String submissionKey;
    @Column(nullable = false) private int generation;
    private String orderId;
    private String outcome;
    private Instant createdAt;
}
