package com.splitexpense.expense.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shape of the {@code expense.voided} Kafka message, published to notification-service.
 *
 * <p>See {@link ExpenseCreatedEvent} for why this carries a whole participant list rather than
 * two ids.
 *
 * @param eventId            unique id of this event, for consumer-side idempotency
 * @param expenseId          id of the voided expense
 * @param groupId            the group it belongs to
 * @param participantUserIds everyone who had a share of the original expense
 * @param amount             the expense's original amount
 * @param currency           ISO 4217 currency code
 * @param description        what the expense was for
 * @param occurredAt         when the void was recorded
 */
public record ExpenseVoidedEvent(
        UUID eventId,
        UUID expenseId,
        UUID groupId,
        List<UUID> participantUserIds,
        BigDecimal amount,
        String currency,
        String description,
        Instant occurredAt) {
}
