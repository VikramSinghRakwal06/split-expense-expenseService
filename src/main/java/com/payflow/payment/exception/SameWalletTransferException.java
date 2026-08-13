package com.payflow.payment.exception;

/**
 * The receiver named in a transfer request is the caller's own wallet.
 *
 * <p>Rejected before any wallet-service call or {@link com.payflow.payment.entity.Transaction}
 * row is created — checked in application code so the caller gets a clear 400 rather than
 * discovering it as a raw {@code ck_transactions_distinct_wallets} constraint violation, which
 * exists as the schema's own backstop, not as the primary defence.
 */
public class SameWalletTransferException extends RuntimeException {

    public SameWalletTransferException(String message) {
        super(message);
    }
}
