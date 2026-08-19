package com.payflow.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shape of the {@code payment.failed} Kafka message, published to notification-service.
 *
 * <p>See {@link PaymentCompletedEvent} for why this is a locally-defined copy of the shared
 * contract rather than a shared library type.
 *
 * @param eventId       unique id of this event, for consumer-side idempotency
 * @param transactionId id of the payment transaction that failed or was reversed
 * @param senderUserId  user who attempted the payment
 * @param amount        attempted payment amount; a monetary value, never floating-point
 * @param reason        human-readable failure reason
 * @param occurredAt    when the failure occurred
 */
public record PaymentFailedEvent(
        UUID eventId,
        UUID transactionId,
        UUID senderUserId,
        BigDecimal amount,
        String reason,
        Instant occurredAt) {
}
