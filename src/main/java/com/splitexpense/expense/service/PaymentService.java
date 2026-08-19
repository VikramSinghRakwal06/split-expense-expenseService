package com.payflow.payment.service;

import com.payflow.payment.client.WalletClient;
import com.payflow.payment.client.dto.WalletBalanceView;
import com.payflow.payment.dto.request.TransferRequest;
import com.payflow.payment.dto.response.TransactionResponse;
import com.payflow.payment.entity.IdempotencyRecord;
import com.payflow.payment.entity.Transaction;
import com.payflow.payment.entity.TransactionStatus;
import com.payflow.payment.event.PaymentEventPublisher;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.exception.InsufficientFundsException;
import com.payflow.payment.exception.SameWalletTransferException;
import com.payflow.payment.exception.TransferFailedException;
import com.payflow.payment.exception.TransferInProgressException;
import com.payflow.payment.exception.TransferReversedException;
import com.payflow.payment.exception.TransferUnresolvedException;
import com.payflow.payment.exception.WalletOperationException;
import com.payflow.payment.exception.WalletServiceUnavailableException;
import com.payflow.payment.mapper.TransactionMapper;
import com.payflow.payment.repository.IdempotencyRecordRepository;
import com.payflow.payment.repository.TransactionRepository;
import com.payflow.payment.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates one idempotent transfer between two wallets — the saga described by the three
 * tables in {@code V1__create_transactions_idempotency_and_audit.sql}.
 *
 * <h2>Why no {@code @Transactional} spans this class</h2>
 *
 * <p>A transfer is several short local writes separated by two remote calls to wallet-service.
 * Wrapping the whole thing in one transaction would hold a database connection for the entire
 * round trip — exactly what {@code payflow.wallet}'s Hikari pool comment in
 * {@code application.yml} says this service is built to avoid: "the connection is returned to
 * the pool between them ... deliberately, so a slow wallet-service exhausts sockets rather
 * than the connection pool." Each local write below is therefore its own short, already-
 * committed transaction (an ordinary {@code JpaRepository.save}), and the wallet-service calls
 * happen entirely outside any of them.
 *
 * <h2>Known limitation: debit-step ambiguity</h2>
 *
 * <p>{@link com.payflow.payment.exception.WalletServiceUnavailableException} on the
 * <em>credit</em> leg is handled by design — see {@link #reverse} — because a compensating
 * credit is always safe to attempt regardless of whether the original credit actually applied.
 * The same is not true of the <em>debit</em> leg: if it times out, this service cannot safely
 * assume either outcome, and wallet-service's money-movement endpoints do not deduplicate by
 * the {@code reference} this service sends them (see {@code WalletTransactionService} in
 * wallet-service — {@code applyMovement} applies unconditionally on every call). So an
 * ambiguous debit is not retried and not compensated; the transaction is left in
 * {@code INITIATED} for reconciliation against wallet-service's ledger, and a retry under the
 * same key reports {@link TransferInProgressException} rather than silently treating an
 * unknown outcome as either success or failure.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String RETRY_SHORTLY =
            "A request with this Idempotency-Key is already being processed. Retry shortly.";

    private final TransactionRepository transactionRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final WalletClient walletClient;
    private final AuditService auditService;
    private final TransactionMapper transactionMapper;
    private final ObjectMapper objectMapper;
    private final PaymentEventPublisher paymentEventPublisher;

    public PaymentService(
            TransactionRepository transactionRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            WalletClient walletClient,
            AuditService auditService,
            TransactionMapper transactionMapper,
            ObjectMapper objectMapper,
            PaymentEventPublisher paymentEventPublisher) {
        this.transactionRepository = transactionRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.walletClient = walletClient;
        this.auditService = auditService;
        this.transactionMapper = transactionMapper;
        this.objectMapper = objectMapper;
        this.paymentEventPublisher = paymentEventPublisher;
    }

    /**
     * Executes (or replays) one transfer from the caller's own wallet to another.
     *
     * @param caller                  the verified caller
     * @param idempotencyKey          the client's {@code Idempotency-Key} header, required
     * @param request                 receiver, amount and optional description
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded verbatim to
     *                                resolve their wallet — never the service account's
     * @return the completed transfer
     * @throws com.payflow.payment.exception.NoWalletException the caller has no wallet
     * @throws SameWalletTransferException     receiver is the caller's own wallet
     * @throws IdempotencyKeyConflictException the key was already used with a different request
     * @throws TransferInProgressException     no terminal outcome yet for this key
     * @throws TransferFailedException         the debit was refused; nothing moved
     * @throws TransferReversedException       the credit failed but was safely compensated
     * @throws TransferUnresolvedException     the credit failed and compensation also failed
     * @throws WalletServiceUnavailableException the debit itself was ambiguous
     */
    public TransactionResponse transfer(
            AuthenticatedUser caller,
            String idempotencyKey,
            TransferRequest request,
            String callerAuthorizationHeader) {

        WalletBalanceView senderWallet = walletClient.getWalletForCaller(callerAuthorizationHeader);
        UUID senderWalletId = senderWallet.id();

        if (senderWalletId.equals(request.receiverWalletId())) {
            throw new SameWalletTransferException("Cannot transfer to your own wallet");
        }

        String requestHash = hashRequest(caller.id(), request);

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        IdempotencyRecord record;
        try {
            record = idempotencyRecordRepository.saveAndFlush(
                    IdempotencyRecord.builder()
                            .key(idempotencyKey)
                            .requestHash(requestHash)
                            .build());
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent request for the same key won the race to reserve it first.
            log.debug("Lost the race to reserve idempotency key {}", idempotencyKey);
            return replay(
                    idempotencyRecordRepository.findById(idempotencyKey)
                            .orElseThrow(() -> new TransferInProgressException(RETRY_SHORTLY)),
                    requestHash);
        }

        return execute(idempotencyKey, senderWalletId, senderWallet.userId(), request, record);
    }

    /**
     * One page of the caller's transfer history, sent and received alike.
     *
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to resolve
     *                                  their wallet
     * @param pageable                  page number, size and sort
     * @return the requested page
     * @throws com.payflow.payment.exception.NoWalletException the caller has no wallet
     */
    public Page<TransactionResponse> getTransactionsForCaller(
            String callerAuthorizationHeader, Pageable pageable) {
        UUID walletId = walletClient.getWalletForCaller(callerAuthorizationHeader).id();
        return transactionRepository
                .findBySenderWalletIdOrReceiverWalletId(walletId, walletId, pageable)
                .map(transactionMapper::toResponse);
    }

    /**
     * Serves a retried request against whatever outcome the original attempt reached (or
     * hasn't reached yet).
     *
     * @throws IdempotencyKeyConflictException the key was reused with a different request body
     */
    private TransactionResponse replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "This Idempotency-Key was already used with a different request");
        }

        if (record.getResponseBody() != null) {
            return deserializeResponse(record.getResponseBody());
        }

        Transaction txn = transactionRepository.findByIdempotencyKey(record.getKey())
                .orElseThrow(() -> new TransferInProgressException(RETRY_SHORTLY));

        return switch (txn.getStatus()) {
            case COMPLETED -> transactionMapper.toResponse(txn);
            case FAILED -> throw new TransferFailedException(txn.getFailureReason());
            case REVERSED -> throw new TransferReversedException(txn.getFailureReason());
            case INITIATED, DEBITED -> throw new TransferInProgressException(RETRY_SHORTLY);
        };
    }

    private TransactionResponse execute(
            String idempotencyKey,
            UUID senderWalletId,
            UUID senderUserId,
            TransferRequest request,
            IdempotencyRecord idempotencyRecord) {

        Transaction txn = Transaction.builder()
                .idempotencyKey(idempotencyKey)
                .senderWalletId(senderWalletId)
                .receiverWalletId(request.receiverWalletId())
                .amount(request.amount())
                .description(request.description())
                .build();
        try {
            txn = transactionRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException raceLost) {
            // uq_transactions_idempotency_key is the true arbiter of this key. Reaching this
            // catch despite having already reserved the idempotency_records row above would
            // mean two requests raced ahead of that reservation; treat it the same way.
            throw new TransferInProgressException(RETRY_SHORTLY);
        }

        auditService.record(txn.getId(), "TRANSFER_INITIATED", "sender=%s receiver=%s amount=%s"
                .formatted(senderWalletId, request.receiverWalletId(), request.amount()));

        String reference = txn.getId().toString();

        try {
            walletClient.debit(senderWalletId, request.amount(), reference,
                    "Transfer to wallet " + request.receiverWalletId());
        } catch (InsufficientFundsException | WalletOperationException ex) {
            markTerminal(txn, TransactionStatus.FAILED, ex.getMessage());
            auditService.record(txn.getId(), "DEBIT_REFUSED", ex.getMessage());
            paymentEventPublisher.publishFailed(txn.getId(), senderUserId, request.amount(), ex.getMessage());
            throw new TransferFailedException(ex.getMessage());
        } catch (WalletServiceUnavailableException ex) {
            // See the class javadoc's "Known limitation" section. Left INITIATED, not FAILED.
            auditService.record(txn.getId(), "DEBIT_AMBIGUOUS_FAILURE", ex.getMessage());
            throw ex;
        }

        txn = markStatus(txn, TransactionStatus.DEBITED);
        auditService.record(txn.getId(), "SENDER_DEBITED", "amount=" + request.amount());

        WalletBalanceView receiverWallet;
        try {
            receiverWallet = walletClient.credit(request.receiverWalletId(), request.amount(), reference,
                    "Transfer from wallet " + senderWalletId);
        } catch (WalletOperationException | WalletServiceUnavailableException ex) {
            throw reverse(txn, senderUserId, ex);
        }

        txn = markTerminal(txn, TransactionStatus.COMPLETED, null);
        auditService.record(txn.getId(), "TRANSFER_COMPLETED", "amount=" + request.amount());
        paymentEventPublisher.publishCompleted(
                txn.getId(), senderUserId, receiverWallet.userId(), txn.getAmount(), txn.getCurrency());

        TransactionResponse response = transactionMapper.toResponse(txn);
        cacheSuccess(idempotencyRecord, response);
        return response;
    }

    /**
     * Issues the compensating credit back to the sender after the receiver's credit failed or
     * was ambiguous, and decides which outcome the caller sees.
     *
     * @return the exception {@link #execute} should throw — never returns normally
     */
    private RuntimeException reverse(Transaction txn, UUID senderUserId, RuntimeException creditFailure) {
        auditService.record(txn.getId(), "CREDIT_FAILED", creditFailure.getMessage());

        try {
            walletClient.credit(
                    txn.getSenderWalletId(),
                    txn.getAmount(),
                    txn.getId() + "-reversal",
                    "Reversal of failed transfer " + txn.getId());
        } catch (WalletOperationException | WalletServiceUnavailableException compensationFailure) {
            log.error("Compensation failed for transaction {}: sender was debited but the "
                            + "reversal credit also failed. Manual reconciliation required.",
                    txn.getId(), compensationFailure);
            auditService.record(txn.getId(), "COMPENSATION_FAILED", compensationFailure.getMessage());
            // Status deliberately stays DEBITED: this is not a settled outcome, so no
            // PaymentFailedEvent either — nothing has actually failed yet, as far as we know.
            return new TransferUnresolvedException(txn.getId());
        }

        txn = markTerminal(txn, TransactionStatus.REVERSED, creditFailure.getMessage());
        auditService.record(txn.getId(), "TRANSFER_REVERSED", creditFailure.getMessage());
        paymentEventPublisher.publishFailed(
                txn.getId(), senderUserId, txn.getAmount(), creditFailure.getMessage());
        return new TransferReversedException(creditFailure.getMessage());
    }

    /**
     * Each {@code save} here is its own already-committed transaction (see the class javadoc),
     * so by the time it returns {@code txn} is detached and {@code save} took the
     * {@code merge()} path — which returns a distinct, newly-managed instance carrying the
     * bumped {@code @Version}, leaving the original instance's version stale. Callers MUST use
     * the returned entity for any further save, or that next save's version check fails
     * against a version the database has already moved past.
     */
    private Transaction markStatus(Transaction txn, TransactionStatus status) {
        txn.setStatus(status);
        return transactionRepository.save(txn);
    }

    private Transaction markTerminal(Transaction txn, TransactionStatus status, String failureReason) {
        txn.setStatus(status);
        txn.setFailureReason(failureReason);
        return transactionRepository.save(txn);
    }

    private void cacheSuccess(IdempotencyRecord record, TransactionResponse response) {
        try {
            record.setResponseBody(objectMapper.writeValueAsString(response));
            record.setHttpStatus(HttpStatus.OK.value());
            idempotencyRecordRepository.save(record);
        } catch (RuntimeException ex) {
            // Losing the cached replay does not lose the money movement: the transaction row
            // is still the source of truth, and #replay rebuilds the response from it.
            log.warn("Could not cache the idempotent response for key {}", record.getKey(), ex);
        }
    }

    private TransactionResponse deserializeResponse(String json) {
        return objectMapper.readValue(json, TransactionResponse.class);
    }

    private String hashRequest(UUID callerId, TransferRequest request) {
        String canonical = callerId + "|" + request.receiverWalletId() + "|"
                + request.amount().toPlainString() + "|" + Objects.toString(request.description(), "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK's standard algorithm list; unreachable in practice.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
