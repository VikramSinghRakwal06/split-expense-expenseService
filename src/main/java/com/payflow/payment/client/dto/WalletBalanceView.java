package com.payflow.payment.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors wallet-service's {@code WalletResponse} field for field.
 *
 * <p>Matching the full shape, rather than declaring only the fields this service reads,
 * avoids relying on unknown-property leniency in the Jackson 3 ({@code tools.jackson})
 * ObjectMapper — this module ships no separate annotations artifact to opt out of strict
 * binding with, unlike classic Jackson 2.
 */
public record WalletBalanceView(
        UUID id,
        UUID userId,
        BigDecimal balance,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
