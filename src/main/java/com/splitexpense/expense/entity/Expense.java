package com.splitexpense.expense.entity;

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
 * One recorded expense — the saga's durable state, and the source record for the deltas
 * applied to group-service's debt graph.
 *
 * <p>This service holds no balances; a row here records what was charged and how far the
 * attempt to apply it got, per {@link ExpenseStatus}. {@code idempotencyKey} is what makes a
 * retried request safe: the unique constraint on it, not application logic, is what stops two
 * concurrent requests carrying the same key from both being recorded.
 *
 * <p>Never serialised to a client; the controller maps it (with its splits) to an
 * {@code ExpenseResponse}.
 */
@Entity
@Table(name = "expenses")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** No foreign key: groups live in group-service, behind a different database. */
    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    /**
     * Who paid. Not necessarily the caller recording the expense — one member may record an
     * expense on behalf of another who covered it in person.
     */
    @Column(name = "payer_user_id", nullable = false, updatable = false)
    private UUID payerUserId;

    /** Always exact decimal; compare with {@code compareTo}, never {@code equals}. */
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, updatable = false, length = 20)
    private SplitType splitType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpenseStatus status = ExpenseStatus.INITIATED;

    /** Why an expense ended FAILED, in words safe to show whoever recorded it. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * The client's {@code Idempotency-Key}, verbatim. The unique constraint on this column
     * is what actually prevents a duplicated expense; see the migration.
     */
    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private String idempotencyKey;

    /**
     * Optimistic-locking counter, managed entirely by Hibernate. One expense row is advanced
     * through its states by one request at a time; this stops a crashed-and-retried attempt
     * from racing a concurrent void and overwriting a state it never observed.
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
        if (!(other instanceof Expense expense)) {
            return false;
        }
        return id != null && id.equals(expense.id);
    }

    @Override
    public int hashCode() {
        return Expense.class.hashCode();
    }
}
