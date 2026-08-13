package com.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One attempt to move money between two wallets — the saga's durable state.
 *
 * <p>This service holds no balances; a row here records what was attempted and how far it
 * got, per {@link TransactionStatus}. {@code idempotencyKey} is what makes a retried request
 * safe: the unique constraint on it, not application logic, is what stops two concurrent
 * requests carrying the same key from both moving money.
 *
 * <p>Never serialised to a client; the controller maps it to a {@code TransactionResponse}.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The client's {@code Idempotency-Key}, verbatim. The unique constraint on this column
     * is what actually prevents a duplicated transfer; see the migration.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "sender_wallet_id", nullable = false, updatable = false)
    private UUID senderWalletId;

    @Column(name = "receiver_wallet_id", nullable = false, updatable = false)
    private UUID receiverWalletId;

    /** Always exact decimal; compare with {@code compareTo}, never {@code equals}. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.INITIATED;

    /** Why a transfer ended FAILED or REVERSED, in words safe to show the payer. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "description")
    private String description;

    /**
     * Optimistic-locking counter, managed entirely by Hibernate. One transaction row is
     * advanced through its states by one request at a time; this stops a crashed-and-retried
     * attempt from racing a concurrent reversal and overwriting a state it never observed.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Identity is the surrogate key alone. A transient instance (null id) is equal only to
     * itself, which keeps unsaved entities from colliding inside collections.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction transaction)) {
            return false;
        }
        return id != null && id.equals(transaction.id);
    }

    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }
}
