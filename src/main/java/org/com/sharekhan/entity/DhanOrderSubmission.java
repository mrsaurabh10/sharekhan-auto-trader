package org.com.sharekhan.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable send-once claim. A timeout must never cause a second order POST. */
@Entity
@Table(name="dhan_order_submissions")
public class DhanOrderSubmission {
    @Id @Column(name="correlation_id", length=30) private String correlationId;
    @Column(name="credential_id", nullable=false) private Long credentialId;
    @Column(name="submission_key", nullable=false, length=80) private String submissionKey;
    @Column(name="order_side", nullable=false, length=4) private String side;
    @Column(name="created_at", nullable=false) private Instant createdAt;
}
