package com.payflow.payment.dto.response;

import com.payflow.payment.entity.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing view of a {@link com.payflow.payment.entity.Transaction}. The only shape a
 * transfer attempt is exposed in.
 *
 * <p>Carries no {@code version}: the optimistic-locking counter is an internal persistence
 * detail. {@code amount} serialises with its full scale preserved, exactly like
 * wallet-service's {@code WalletResponse.balance}.
 *
 * @param id                transaction identifier
 * @param senderWalletId    wallet debited
 * @param receiverWalletId  wallet credited
 * @param amount            magnitude moved, exact decimal
 * @param currency          ISO-4217 code
 * @param status            where this transfer is in its state machine
 * @param failureReason     why it ended FAILED or REVERSED, safe to show the payer; null
 *                          otherwise
 * @param description       caller-supplied note, may be null
 * @param createdAt         when the transfer was initiated
 * @param updatedAt         when its status last changed
 */
@Schema(description = "A transfer attempt and its current state")
public record TransactionResponse(
        UUID id,
        UUID senderWalletId,
        UUID receiverWalletId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        String failureReason,
        String description,
        Instant createdAt,
        Instant updatedAt) {
}
