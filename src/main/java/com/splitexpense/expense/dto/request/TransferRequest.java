package com.payflow.payment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload for {@code POST /api/v1/payments/transfer}.
 *
 * <p>Carries no sender wallet id: the sender is always the authenticated caller's own wallet,
 * resolved from the JWT via wallet-service — never a parameter, so there is no field through
 * which one user could move money out of another user's wallet.
 *
 * @param receiverWalletId wallet-service id of the wallet to credit
 * @param amount           positive sum to move, up to 4 decimal places
 * @param description      optional human-readable note, carried onto both wallets' ledger
 *                         entries
 */
@Schema(description = "Request to transfer money from the caller's wallet to another")
public record TransferRequest(

        @Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull(message = "Receiver wallet id is required")
        UUID receiverWalletId,

        @Schema(example = "250.0000", description = "Positive amount, up to 4 decimal places")
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        // Mirrors NUMERIC(19,4): 15 integer digits + 4 fractional.
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @Schema(example = "Dinner split")
        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description) {
}
