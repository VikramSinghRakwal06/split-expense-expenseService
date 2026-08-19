package com.splitexpense.expense.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/settlements}.
 *
 * <p>Carries no field naming the caller: {@code SettlementService} accepts a settlement from
 * either party to the debt — {@code fromUserId} recording having paid, or the recipient
 * recording having received it — provided the caller is one of the two named users and both
 * are current members of the group. See {@code SettlementService} for exactly which callers
 * are accepted and why.
 *
 * @param groupId    the group this settlement belongs to
 * @param fromUserId who paid
 * @param toUserId   who received it
 * @param amount     the amount settled, up to 4 decimal places
 * @param note       optional human-readable note
 */
@Schema(description = "Request to record a real-world payment that settles part of a debt")
public record CreateSettlementRequest(

        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull(message = "Group id is required")
        UUID groupId,

        @Schema(example = "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b")
        @NotNull(message = "From-user id is required")
        UUID fromUserId,

        @Schema(example = "a0c4f912-7d36-4b81-93ea-5f28c6d40719")
        @NotNull(message = "To-user id is required")
        UUID toUserId,

        @Schema(example = "450.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @Schema(example = "Paid back via UPI")
        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note) {
}
