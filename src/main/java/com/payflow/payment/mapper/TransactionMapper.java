package com.payflow.payment.mapper;

import com.payflow.payment.dto.response.TransactionResponse;
import com.payflow.payment.entity.Transaction;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Transaction} entities into their client-facing representation.
 *
 * <p>Kept as an explicit component rather than a mapping framework, mirroring
 * wallet-service's {@code WalletMapper}: one translation, hand-written so that excluding
 * {@code version} is an obvious choice rather than a generator's default.
 */
@Component
public class TransactionMapper {

    /**
     * @param transaction entity to convert, never null
     * @return the safe-to-serialise view of that transaction
     */
    public TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getSenderWalletId(),
                transaction.getReceiverWalletId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getFailureReason(),
                transaction.getDescription(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt());
    }
}
