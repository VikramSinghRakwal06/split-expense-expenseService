package com.splitexpense.expense.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shape of the {@code settlement.recorded} Kafka message, published to notification-service.
 *
 * <p>Two-party, like the payment platform's events, because a settlement genuinely is between
 * exactly two people: {@code toUserId} is who wants to know they were paid back.
 *
 * @param eventId     unique id of this event, for consumer-side idempotency
 * @param settlementId id of the recorded settlement
 * @param groupId     the group it belongs to
 * @param fromUserId  who paid
 * @param toUserId    who received it
 * @param amount      the amount settled; a monetary value, never floating-point
 * @param currency    ISO 4217 currency code
 * @param occurredAt  when the settlement was recorded
 */
public record SettlementRecordedEvent(
        UUID eventId,
        UUID settlementId,
        UUID groupId,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
