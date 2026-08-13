package com.payflow.payment.exception;

import java.util.UUID;

/**
 * The sender was debited, the receiver could not be credited, and the compensating credit
 * back to the sender <strong>also</strong> failed.
 *
 * <p>The one outcome this service cannot resolve on its own: the transaction is left in
 * {@code DEBITED} rather than a terminal state, money is out of the sender's wallet with
 * nothing to show for it, and only a human or an out-of-band reconciliation job — reading
 * wallet-service's ledger against this transaction's id — can determine what actually
 * happened and put it right.
 *
 * <p>Logged at {@code error} the moment this is raised; see {@code PaymentService}. Surfaces
 * to the client as 500, deliberately without the transfer's usual specificity: this is the
 * one response that must tell a caller "stop, do not simply retry" rather than imply the
 * transfer is safely resendable.
 */
public class TransferUnresolvedException extends RuntimeException {

    public TransferUnresolvedException(UUID transactionId) {
        super("Transfer " + transactionId + " could not be completed and automatic recovery "
                + "failed. Do not retry; contact support with this transaction id.");
    }
}
