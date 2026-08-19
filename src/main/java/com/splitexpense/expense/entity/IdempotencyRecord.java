package com.splitexpense.expense.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The replay cache for one client-supplied {@code Idempotency-Key}, shared by expenses and
 * settlements alike — the key space is the client's to manage, not scoped by operation type.
 *
 * <p>A row is inserted before the operation is attempted, carrying only the key and a
 * fingerprint of the request body. {@code responseBody} and {@code httpStatus} are filled in
 * once it reaches a terminal state, so a request that crashed mid-flight leaves a row with no
 * reply yet — a client retrying that key sees no record to replay and the operation is
 * (safely) attempted again, arbitrated by {@code uq_expenses_idempotency_key} or
 * {@code uq_settlements_idempotency_key} on whichever entity the key was used for.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyRecord {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /**
     * Hash of the request this key was first used with. A client reusing a key with a
     * different body has a bug; the mismatch is rejected rather than silently replaying an
     * answer to a different question.
     */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    /** The serialised reply, replayed byte for byte on a duplicate request. Null until the
     *  operation reaches a terminal state. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyRecord record)) {
            return false;
        }
        return key != null && key.equals(record.key);
    }

    @Override
    public int hashCode() {
        return IdempotencyRecord.class.hashCode();
    }
}
