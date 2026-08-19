package com.splitexpense.expense.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One participant's share of an {@link ExpenseResponse}.
 *
 * @param userId      the participant
 * @param shareAmount their share, exact decimal
 */
@Schema(description = "One participant's share of an expense")
public record ExpenseSplitResponse(UUID userId, BigDecimal shareAmount) {
}
