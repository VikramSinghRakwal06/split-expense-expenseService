package com.payflow.payment.exception;

/**
 * The sender's wallet does not hold enough to cover the transfer — wallet-service answered
 * the debit with 422.
 *
 * <p>Modelled separately from the other business refusals in
 * {@link WalletOperationException} because it is the routine outcome of an ordinary,
 * correctly-addressed transfer, and the payer needs to be told exactly this rather than a
 * generic rejection.
 *
 * <p>Carries no balance figure, and must not. This service never learns what the sender
 * holds, and echoing a balance into an error body would leak it to whoever triggered the
 * request.
 *
 * <p>Surfaces to the client as 422, and leaves the transaction in {@code FAILED}. No
 * compensation is required: the debit was refused, so nothing moved.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
