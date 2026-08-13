package com.payflow.payment.entity;

/**
 * State machine of a {@link Transaction}, and the audit trail's vocabulary for what happened
 * to a transfer.
 *
 * <p>Persisted as its {@code name()} string, mirrored by the {@code ck_transactions_status}
 * check constraint in {@code V1__create_transactions_idempotency_and_audit.sql}. Adding a
 * constant here requires a migration that widens that constraint.
 *
 * <p>The only path a transfer can take:
 * <pre>
 * INITIATED -&gt; DEBITED -&gt; COMPLETED
 *            \-&gt; FAILED           (debit refused; nothing moved)
 *   DEBITED  -&gt; REVERSED          (credit ambiguous or refused; compensating credit issued)
 * </pre>
 */
public enum TransactionStatus {

    /** Row inserted, sender not yet debited. Exists so a crash before the debit call is
     *  still visible as an attempt rather than leaving no trace at all. */
    INITIATED,

    /** Sender's wallet debited; receiver not yet credited. The only state in which a
     *  compensating credit may be owed. */
    DEBITED,

    /** Both legs applied. Terminal. */
    COMPLETED,

    /** The debit was refused, or the request was invalid before any call was made. Terminal;
     *  no compensation is required because no money moved. */
    FAILED,

    /** The debit succeeded but the credit could not be trusted to have failed cleanly, or was
     *  itself refused, so a compensating credit returned the money to the sender. Terminal. */
    REVERSED
}
