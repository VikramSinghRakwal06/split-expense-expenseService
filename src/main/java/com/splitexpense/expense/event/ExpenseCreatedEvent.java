package com.splitexpense.expense.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shape of the {@code expense.created} Kafka message, published to notification-service.
 *
 * <p>This is the producer's own copy of a contract shared across services — mirrored field
 * for field by notification-service's local {@code ExpenseCreatedEvent}, not a type either
 * service depends on the other to provide.
 *
 * <p>Unlike the payment platform's events, which named exactly two people (sender and
 * receiver), this one carries a whole participant list: one expense touches every member it
 * was split between, and notification-service turns this single Kafka record into one
 * notification row per participant. See its {@code PaymentEventListener} equivalent for how
 * that fan-out stays exactly-once under Kafka's at-least-once delivery.
 *
 * @param eventId          unique id of this event, so a consumer redelivered the same Kafka
 *                        record can recognise and skip it
 * @param expenseId        id of the completed expense
 * @param groupId          the group it belongs to
 * @param payerUserId      who paid
 * @param participantUserIds everyone with a share of this expense, payer included
 * @param amount           the expense's full amount; a monetary value, never floating-point
 * @param currency         ISO 4217 currency code
 * @param description      what the expense was for
 * @param occurredAt       when the expense was recorded
 */
public record ExpenseCreatedEvent(
        UUID eventId,
        UUID expenseId,
        UUID groupId,
        UUID payerUserId,
        List<UUID> participantUserIds,
        BigDecimal amount,
        String currency,
        String description,
        Instant occurredAt) {
}
