package com.payflow.payment.client.dto;

import java.time.Instant;

/**
 * Mirrors wallet-service's {@code ErrorResponse} field for field — see
 * {@link WalletBalanceView} for why the full shape is declared rather than just the one field
 * ({@code message}) this service actually reads.
 */
public record WalletErrorBody(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Object validationErrors) {
}
