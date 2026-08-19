package com.splitexpense.expense.dto.response;

import com.splitexpense.expense.entity.ExpenseStatus;
import com.splitexpense.expense.entity.SplitType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Client-facing view of an {@link com.splitexpense.expense.entity.Expense} and its splits. The
 * only shape an expense is exposed in.
 *
 * <p>Carries no {@code version}: the optimistic-locking counter is an internal persistence
 * detail. {@code amount} and each split's {@code shareAmount} serialise with their full scale
 * preserved, exactly like group-service's {@code GroupBalancesResponse}.
 *
 * @param id            expense identifier
 * @param groupId       the group it belongs to
 * @param payerUserId   who paid
 * @param amount        the full amount, exact decimal
 * @param currency      ISO-4217 code
 * @param description   what the expense was for
 * @param splitType     how the amount was divided
 * @param status        where this expense is in its state machine
 * @param failureReason why it ended FAILED, safe to show whoever recorded it; null otherwise
 * @param splits        each participant's share
 * @param createdAt     when the expense was recorded
 * @param updatedAt     when its status last changed
 */
@Schema(description = "An expense and how it was split")
public record ExpenseResponse(
        UUID id,
        UUID groupId,
        UUID payerUserId,
        BigDecimal amount,
        String currency,
        String description,
        SplitType splitType,
        ExpenseStatus status,
        String failureReason,
        List<ExpenseSplitResponse> splits,
        Instant createdAt,
        Instant updatedAt) {
}
