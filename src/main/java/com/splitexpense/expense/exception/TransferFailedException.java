package com.payflow.payment.exception;

/**
 * The sender's wallet could not be debited — insufficient funds, or any other business
 * refusal wallet-service gave for the debit leg.
 *
 * <p>Terminal, and requires no compensation: wallet-service refused before anything moved, so
 * there is nothing to reverse. The two refusal reasons are deliberately not distinguished by
 * a different status code here — payment-service's own contract only needs to say "this
 * transfer did not happen, and here is why" ({@link #getMessage()} carries the specific
 * reason); the finer distinction inside wallet-service's response does not need to leak
 * through as a different status two hops away.
 */
public class TransferFailedException extends RuntimeException {

    public TransferFailedException(String message) {
        super(message);
    }
}
