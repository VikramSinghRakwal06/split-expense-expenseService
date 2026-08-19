package com.payflow.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shape of the {@code payment.completed} Kafka message, published to notification-service.
 *
 * <p>This is the producer's own copy of a contract shared across services — mirrored field
 * for field by notification-service's local {@code PaymentCompletedEvent}, not a type either
 * service depends on the other to provide. See that class's javadoc for why.
 *
 * @param eventId        unique id of this event, so a consumer redelivered the same Kafka
 *                       record (at-least-once delivery) can recognise and skip it
 * @param transactionId  id of the completed payment transaction
 * @param senderUserId   user who sent the payment
 * @param receiverUserId user who received the payment
 * @param amount         payment amount; a monetary value, never a floating-point type
 * @param currency       ISO 4217 currency code
 * @param occurredAt     when the payment completed
 */
public record PaymentCompletedEvent(
        UUID eventId,
        UUID transactionId,
        UUID senderUserId,
        UUID receiverUserId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
