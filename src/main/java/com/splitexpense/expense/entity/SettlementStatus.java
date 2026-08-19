package com.splitexpense.expense.entity;

/**
 * State machine of a {@link Settlement}. Mirrored by {@code ck_settlements_status}.
 *
 * <p>Simpler than {@link ExpenseStatus}: a settlement is never voided — recording a payment
 * that later turns out to be wrong is corrected by recording a second settlement in the
 * opposite direction, the same way group-service's append-only ledger treats any other
 * correction, not by un-recording the first.
 */
public enum SettlementStatus {

    /** Row inserted, delta not yet applied. */
    INITIATED,

    /** Delta applied to group-service. Terminal. */
    COMPLETED,

    /** The apply was refused. Terminal; no compensation required because no balance
     *  changed. */
    FAILED
}
