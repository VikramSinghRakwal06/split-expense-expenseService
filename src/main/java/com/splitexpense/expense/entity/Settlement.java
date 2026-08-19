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
 * A real-world payment between two group members, recorded to cancel part or all of what one
 * owed the other.
 *
 * <p>Runs the same single-delta saga as an {@link Expense}, against the same group-service
 * {@code :apply} endpoint, and for the same reason: this service records that money moved
 * outside the platform, it does not move any itself.
 */
@Entity
@Table(name = "settlements")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    /** Who paid. */
    @Column(name = "from_user_id", nullable = false, updatable = false)
    private UUID fromUserId;

    /** Who received it. */
    @Column(name = "to_user_id", nullable = false, updatable = false)
    private UUID toUserId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency = "INR";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SettlementStatus status = SettlementStatus.INITIATED;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "note", length = 255)
    private String note;

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
        if (!(other instanceof Settlement settlement)) {
            return false;
        }
        return id != null && id.equals(settlement.id);
    }

    @Override
    public int hashCode() {
        return Settlement.class.hashCode();
    }
}
