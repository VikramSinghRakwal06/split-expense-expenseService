package com.splitexpense.expense.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One participant's share of one {@link Expense}.
 *
 * <p>The payer normally appears here too, with their own share — they paid the whole amount
 * but only consumed part of it, and {@code ExpenseService} is what excludes exactly that row
 * when building the deltas sent to group-service: the payer cannot owe themselves.
 *
 * <p>Immutable once written, in spirit if not by column-level enforcement: an expense's splits
 * are computed once by {@code com.splitexpense.expense.domain.SplitCalculator} and never
 * revised. A mistake is corrected by voiding the expense and recording a new one, exactly as
 * group-service's ledger is corrected by appending an opposite entry rather than editing the
 * original.
 */
@Entity
@Table(name = "expense_splits")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExpenseSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "expense_id", nullable = false, updatable = false)
    private UUID expenseId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * This participant's share of the expense's total. Non-negative; may be zero for an
     * {@code EXACT} split that deliberately names somebody for nothing.
     */
    @Column(name = "share_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal shareAmount;

    /**
     * Identity is the surrogate key alone. A transient instance (null id) is equal only to
     * itself, which keeps unsaved entities from colliding inside collections.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExpenseSplit split)) {
            return false;
        }
        return id != null && id.equals(split.id);
    }

    @Override
    public int hashCode() {
        return ExpenseSplit.class.hashCode();
    }
}
