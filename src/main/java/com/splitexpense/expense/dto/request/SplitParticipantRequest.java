package com.splitexpense.expense.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One participant's input to an expense's split, as part of {@link CreateExpenseRequest}.
 *
 * <p>{@code value} means something different for each {@link com.splitexpense.expense.entity.SplitType}
 * and is entirely optional for {@code EQUAL} — see {@code SplitCalculator.ParticipantInput},
 * which this is mapped onto unchanged, for the precise interpretation and validation of each
 * case.
 *
 * @param userId the participant, as minted by auth-service; must be a current member of the
 *               expense's group
 * @param value  ignored for {@code EQUAL}; the exact share for {@code EXACT}; the percentage
 *               (0–100) for {@code PERCENTAGE}; the integer weight for {@code SHARES}
 */
@Schema(description = "One participant's share input for an expense split")
public record SplitParticipantRequest(

        @Schema(example = "b1e7d3c2-4a58-4f19-9d63-2e8a7c015f4b")
        @NotNull(message = "Participant user id is required")
        UUID userId,

        @Schema(example = "450.00",
                description = "Meaning depends on splitType: exact amount, percentage, or "
                        + "share weight. Ignored for EQUAL.")
        BigDecimal value) {
}
