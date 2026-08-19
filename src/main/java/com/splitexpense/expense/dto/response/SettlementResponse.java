package com.splitexpense.expense.dto.response;

import com.splitexpense.expense.entity.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing view of a {@link com.splitexpense.expense.entity.Settlement}. The only shape a
 * settlement is exposed in.
 *
 * @param id            settlement identifier
 * @param groupId       the group it belongs to
 * @param fromUserId    who paid
 * @param toUserId      who received it
 * @param amount        the amount settled, exact decimal
 * @param currency      ISO-4217 code
 * @param status        where this settlement is in its state machine
 * @param failureReason why it ended FAILED, safe to show either party; null otherwise
 * @param note          caller-supplied note, may be null
 * @param createdAt     when the settlement was recorded
 * @param updatedAt     when its status last changed
 */
@Schema(description = "A real-world payment recorded to cancel part of a debt")
public record SettlementResponse(
        UUID id,
        UUID groupId,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        String currency,
        SettlementStatus status,
        String failureReason,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
