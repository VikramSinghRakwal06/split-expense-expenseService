package com.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only record of a state transition or remote-call outcome for one transfer attempt.
 *
 * <p>Written by {@code AuditService} in its own {@code REQUIRES_NEW} transaction, so the
 * trail of a failed payment survives the rollback of the payment itself — the failures are
 * precisely the ones worth being able to reconstruct afterwards.
 *
 * <p>{@code transactionId} is nullable and deliberately not a foreign key, even though the
 * target table lives in this same database: an audit row must be writable for an event that
 * happens before the transaction row is inserted, or after it could not be.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Column(name = "event", nullable = false, updatable = false, length = 50)
    private String event;

    @Column(name = "details", columnDefinition = "TEXT", updatable = false)
    private String details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuditLog auditLog)) {
            return false;
        }
        return id != null && id.equals(auditLog.id);
    }

    @Override
    public int hashCode() {
        return AuditLog.class.hashCode();
    }
}
