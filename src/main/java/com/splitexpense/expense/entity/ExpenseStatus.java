package com.splitexpense.expense.entity;

/**
 * State machine of an {@link Expense}, and the audit trail's vocabulary for what happened to
 * it.
 *
 * <p>Persisted as its {@code name()} string, mirrored by the {@code ck_expenses_status} check
 * constraint. Adding a constant here requires a migration that widens that constraint.
 *
 * <p>The only path an expense can take:
 * <pre>
 * INITIATED -&gt; COMPLETED -&gt; VOIDED
 *           \-&gt; FAILED
 * </pre>
 *
 * <p>Notably absent is anything resembling {@code Transaction}'s old {@code DEBITED} state.
 * That state existed because a transfer was two separate remote calls with a window between
 * them in which money had moved on one side but not the other. An expense applies its deltas
 * in a single call to group-service's {@code :apply} endpoint, which is atomic and idempotent
 * by the expense's own id — so there is no intermediate state to be stranded in, and
 * therefore no compensation logic needed at all. See {@code ExpenseService} for the saga this
 * status machine drives.
 */
public enum ExpenseStatus {

    /** Row inserted, deltas not yet applied. Exists so a crash before the apply call is
     *  still visible as an attempt rather than leaving no trace at all. */
    INITIATED,

    /** Deltas applied to group-service. Terminal. */
    COMPLETED,

    /** The apply was refused — an unknown group, an archived one, or a participant who is
     *  not (or no longer) a member. Terminal; no compensation is required because no
     *  balance changed. */
    FAILED,

    /** A completed expense whose deltas were reversed by an equal and opposite apply.
     *  Terminal; the expense and its splits remain as a record of what was originally
     *  charged. */
    VOIDED
}
