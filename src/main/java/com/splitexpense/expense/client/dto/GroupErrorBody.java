package com.splitexpense.expense.client.dto;

import java.time.Instant;

/**
 * Mirrors group-service's {@code ErrorResponse} field for field — see {@link GroupView} for
 * why the full shape is declared rather than just the one field ({@code message}) this
 * service actually reads.
 */
public record GroupErrorBody(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Object validationErrors) {
}
