package com.splitexpense.expense.dto.request;

import com.splitexpense.expense.entity.SplitType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/expenses}.
 *
 * <p>{@code payerUserId} is deliberately a field rather than always the caller: one member
 * frequently records an expense on somebody else's behalf ("Priya paid for the cab, put it
 * through"). What the caller cannot do is record an expense in a group they do not belong to
 * — {@code ExpenseService} resolves the group through group-service using the caller's own
 * token, and a non-member is refused there before any of this payload is even validated
 * against the group's membership.
 *
 * @param groupId     the group this expense belongs to
 * @param payerUserId who paid; must be a current member of the group
 * @param amount      the expense's full amount, up to 4 decimal places
 * @param description what the expense was for
 * @param splitType   how {@code amount} is divided between {@code participants}
 * @param participants who has a share of this expense, and their split-specific input; the
 *                     payer normally appears here too, with their own share
 */
@Schema(description = "Request to record a new expense and split it between participants")
public record CreateExpenseRequest(

        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull(message = "Group id is required")
        UUID groupId,

        @Schema(example = "a0c4f912-7d36-4b81-93ea-5f28c6d40719")
        @NotNull(message = "Payer user id is required")
        UUID payerUserId,

        @Schema(example = "1350.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        // Mirrors NUMERIC(19,4): 15 integer digits + 4 fractional.
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @Schema(example = "Dinner at Toit")
        @NotEmpty(message = "Description is required")
        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description,

        @Schema(example = "EQUAL")
        @NotNull(message = "Split type is required")
        SplitType splitType,

        @Schema(description = "Who has a share of this expense, and their split-specific input")
        @NotEmpty(message = "At least one participant is required")
        @Size(max = 100, message = "An expense may have at most 100 participants")
        @Valid
        List<SplitParticipantRequest> participants) {
}
