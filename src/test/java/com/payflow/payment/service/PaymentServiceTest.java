package com.payflow.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the transfer saga, with every collaborator mocked except
 * {@link TransactionMapper} — a pure, dependency-free function that is more useful exercised
 * for real than replaced with a mock that would just echo back whatever the test told it to.
 *
 * <p>{@code idempotencyKey}, wallet ids, and the transaction id are all fixed constants across
 * tests: what varies is wallet-service's behaviour, not the identifiers, so keeping them
 * unique per test would add noise without adding coverage.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String KEY = "idem-key-1";
    private static final String AUTH_HEADER = "Bearer caller-token";
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID SENDER_WALLET_ID = UUID.randomUUID();
    private static final UUID RECEIVER_WALLET_ID = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();
    private static final AuthenticatedUser CALLER =
            new AuthenticatedUser(CALLER_ID, "ada@payflow.io", "USER");

    @Mock private TransactionRepository transactionRepository;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private WalletClient walletClient;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private PaymentEventPublisher paymentEventPublisher;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(
                transactionRepository,
                idempotencyRecordRepository,
                walletClient,
                auditService,
                new TransactionMapper(),
                objectMapper,
                paymentEventPublisher);

        // markStatus/markTerminal rely on save()'s return value carrying the bumped @Version
        // forward (see their javadoc); echoing the argument back keeps that contract true for
        // tests without asserting on version numbers specifically. lenient: not every test
        // reaches a save() call.
        lenient().when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static WalletBalanceView walletView(UUID walletId) {
        return new WalletBalanceView(
                walletId, UUID.randomUUID(), new BigDecimal("1000.0000"), "INR", "ACTIVE",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    }

    private static TransferRequest request() {
        return new TransferRequest(RECEIVER_WALLET_ID, new BigDecimal("50.0000"), "lunch");
    }

    /**
     * Mirrors {@code PaymentService#hashRequest} exactly, so fixtures can produce a hash that
     * agrees with what the service itself would compute for a given request — the private
     * method itself is not exposed for tests to call.
     */
    private static String hashOf(TransferRequest request) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String canonical = CALLER_ID + "|" + request.receiverWalletId() + "|"
                    + request.amount().toPlainString() + "|" + request.description();
            return java.util.HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stubs a successful key reservation: caller's wallet resolved, key unused, room reserved. */
    private void stubReservation() {
        when(walletClient.getWalletForCaller(AUTH_HEADER)).thenReturn(walletView(SENDER_WALLET_ID));
        when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** {@link #stubReservation()} plus a transaction row that persists with an assigned id —
     *  the common starting point for every test that reaches the debit or credit leg. */
    private void stubFreshKey(TransferRequest request) {
        stubReservation();
        when(transactionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Transaction txn = invocation.getArgument(0);
            txn.setId(TXN_ID);
            return txn;
        });
    }

    @Nested
    @DisplayName("transfer — validation and idempotency reservation")
    class TransferEntry {

        @Test
        @DisplayName("rejects a transfer to the caller's own wallet before touching either repository")
        void rejectsSameWalletTransfer() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            TransferRequest toSelf = new TransferRequest(
                    SENDER_WALLET_ID, new BigDecimal("10.00"), null);

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, toSelf, AUTH_HEADER))
                    .isInstanceOf(SameWalletTransferException.class);

            verify(idempotencyRecordRepository, never()).findById(any());
            verify(transactionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a lost reservation race is served as a replay, not a duplicate attempt")
        void lostReservationRaceIsReplayed() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.empty());
            when(idempotencyRecordRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_idempotency_records_pkey"));

            Transaction completed = Transaction.builder()
                    .idempotencyKey(KEY)
                    .senderWalletId(SENDER_WALLET_ID)
                    .receiverWalletId(RECEIVER_WALLET_ID)
                    .amount(request().amount())
                    .description(request().description())
                    .build();
            completed.setId(TXN_ID);
            completed.setStatus(TransactionStatus.COMPLETED);

            IdempotencyRecord winner = IdempotencyRecord.builder()
                    .key(KEY)
                    .requestHash(hashOf(request()))
                    .build();
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.empty(), Optional.of(winner));
            when(transactionRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.of(completed));

            TransactionResponse response = service.transfer(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response.id()).isEqualTo(TXN_ID);
            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            verify(transactionRepository, never()).save(any());
            verify(walletClient, never()).debit(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("transfer — replay of an existing idempotency key")
    class Replay {

        private IdempotencyRecord recordWith(String requestHash, String cachedBody) {
            return IdempotencyRecord.builder()
                    .key(KEY)
                    .requestHash(requestHash)
                    .responseBody(cachedBody)
                    .build();
        }

        @Test
        @DisplayName("a key reused with a different request body is rejected, not replayed")
        void mismatchedBodyIsConflict() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith("a-different-hash", null)));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(IdempotencyKeyConflictException.class);

            verify(transactionRepository, never()).findByIdempotencyKey(any());
        }

        @Test
        @DisplayName("a cached response body is replayed byte for byte, without re-touching the transaction")
        void cachedResponseIsReplayedDirectly() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            String matchingHash = hashOf(request());
            TransactionResponse cached = new TransactionResponse(
                    TXN_ID, SENDER_WALLET_ID, RECEIVER_WALLET_ID, request().amount(), "INR",
                    TransactionStatus.COMPLETED, null, "lunch",
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"));
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith(matchingHash, "{cached json}")));
            when(objectMapper.readValue("{cached json}", TransactionResponse.class))
                    .thenReturn(cached);

            TransactionResponse response = service.transfer(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response).isEqualTo(cached);
            verify(transactionRepository, never()).findByIdempotencyKey(any());
        }

        @Test
        @DisplayName("no cached body and no transaction row yet means the first attempt is still in flight")
        void noRowYetIsInProgress() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            String matchingHash = hashOf(request());
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith(matchingHash, null)));
            when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferInProgressException.class);
        }

        @Test
        @DisplayName("a FAILED transaction replays as the original failure, not a fresh 200")
        void failedTransactionReplaysAsFailure() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            String matchingHash = hashOf(request());
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith(matchingHash, null)));
            Transaction failed = Transaction.builder().build();
            failed.setStatus(TransactionStatus.FAILED);
            failed.setFailureReason("Wallet has insufficient funds");
            when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(failed));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferFailedException.class)
                    .hasMessage("Wallet has insufficient funds");
        }

        @Test
        @DisplayName("a REVERSED transaction replays as reversed")
        void reversedTransactionReplaysAsReversed() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            String matchingHash = hashOf(request());
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith(matchingHash, null)));
            Transaction reversed = Transaction.builder().build();
            reversed.setStatus(TransactionStatus.REVERSED);
            reversed.setFailureReason("wallet-service responded with 502");
            when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(reversed));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferReversedException.class);
        }

        @Test
        @DisplayName("an INITIATED or DEBITED transaction is still in progress, never re-attempted")
        void nonTerminalTransactionIsInProgress() {
            when(walletClient.getWalletForCaller(AUTH_HEADER))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            String matchingHash = hashOf(request());
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.of(recordWith(matchingHash, null)));
            Transaction debited = Transaction.builder().build();
            debited.setStatus(TransactionStatus.DEBITED);
            when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(debited));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferInProgressException.class);

            verify(walletClient, never()).debit(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("transfer — first attempt, the debit leg")
    class DebitLeg {

        @Test
        @DisplayName("a race lost against the transaction's own unique constraint is in-progress, not a 500")
        void transactionInsertRaceIsInProgress() {
            stubReservation();
            when(transactionRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_transactions_idempotency_key"));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferInProgressException.class);

            verify(walletClient, never()).debit(any(), any(), any(), any());
        }

        @Test
        @DisplayName("insufficient funds fails the transfer terminally; nothing is compensated")
        void insufficientFundsFailsTerminally() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new InsufficientFundsException("Wallet has insufficient funds"));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferFailedException.class)
                    .hasMessage("Wallet has insufficient funds");

            verify(walletClient, never()).credit(any(), any(), any(), any());
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository, times(1)).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("a business refusal on the debit fails the transfer terminally, same as insufficient funds")
        void debitRefusalFailsTerminally() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new WalletOperationException(409, "Wallet is FROZEN"));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferFailedException.class);

            verify(walletClient, never()).credit(any(), any(), any(), any());
        }

        @Test
        @DisplayName("an ambiguous debit leaves the transaction INITIATED for reconciliation, not FAILED")
        void ambiguousDebitStaysInitiated() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new WalletServiceUnavailableException("wallet-service was unreachable"));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(WalletServiceUnavailableException.class);

            // No status transition at all — the row is exactly as saveAndFlush left it.
            verify(transactionRepository, never()).save(any());
            verify(walletClient, never()).credit(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("transfer — the credit leg and its compensation")
    class CreditLeg {

        @Test
        @DisplayName("a refused credit is reversed with a compensating credit back to the sender")
        void refusedCreditIsReversed() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(walletClient.credit(eq(RECEIVER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new WalletOperationException(409, "Wallet is CLOSED"));
            when(walletClient.credit(eq(SENDER_WALLET_ID), any(), eq(TXN_ID + "-reversal"), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferReversedException.class);

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository, times(2)).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.REVERSED);
        }

        @Test
        @DisplayName("an ambiguous credit is also reversed — the same compensating action covers both")
        void ambiguousCreditIsReversed() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(walletClient.credit(eq(RECEIVER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new WalletServiceUnavailableException("wallet-service was unreachable"));
            when(walletClient.credit(eq(SENDER_WALLET_ID), any(), eq(TXN_ID + "-reversal"), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferReversedException.class);
        }

        @Test
        @DisplayName("when the compensating credit also fails, the transaction is left DEBITED for a human")
        void doubleFailureLeavesTransactionUnresolved() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(walletClient.credit(eq(RECEIVER_WALLET_ID), any(), any(), any()))
                    .thenThrow(new WalletOperationException(409, "Wallet is CLOSED"));
            when(walletClient.credit(eq(SENDER_WALLET_ID), any(), eq(TXN_ID + "-reversal"), any()))
                    .thenThrow(new WalletServiceUnavailableException("wallet-service was unreachable"));

            assertThatThrownBy(() -> service.transfer(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(TransferUnresolvedException.class);

            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            // Only the DEBITED transition was saved; reverse()'s failure path never calls
            // markTerminal, so the row is left exactly at DEBITED.
            verify(transactionRepository, times(1)).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.DEBITED);
        }

        @Test
        @DisplayName("a completed transfer is cached under its idempotency key for byte-identical replay")
        void completedTransferIsCached() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(walletClient.credit(eq(RECEIVER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(RECEIVER_WALLET_ID));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"status\":\"COMPLETED\"}");

            TransactionResponse response = service.transfer(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(response.senderWalletId()).isEqualTo(SENDER_WALLET_ID);
            assertThat(response.receiverWalletId()).isEqualTo(RECEIVER_WALLET_ID);

            ArgumentCaptor<IdempotencyRecord> cached = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(idempotencyRecordRepository).save(cached.capture());
            assertThat(cached.getValue().getResponseBody()).isEqualTo("{\"status\":\"COMPLETED\"}");
        }

        @Test
        @DisplayName("a failure to serialise the cached response does not fail the (already money-moved) transfer")
        void cacheFailureDoesNotFailTheTransfer() {
            stubFreshKey(request());
            when(walletClient.debit(eq(SENDER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(SENDER_WALLET_ID));
            when(walletClient.credit(eq(RECEIVER_WALLET_ID), any(), any(), any()))
                    .thenReturn(walletView(RECEIVER_WALLET_ID));
            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new RuntimeException("serialisation exploded"));

            TransactionResponse response = service.transfer(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
            verify(idempotencyRecordRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getTransactionsForCaller")
    class History {

        @Test
        @DisplayName("resolves the wallet from the token and pages its transactions, mapped to DTOs")
        void returnsMappedPageForCallersWallet() {
            when(walletClient.getWalletForCaller(AUTH_HEADER)).thenReturn(walletView(SENDER_WALLET_ID));
            Transaction txn = Transaction.builder()
                    .idempotencyKey(KEY)
                    .senderWalletId(SENDER_WALLET_ID)
                    .receiverWalletId(RECEIVER_WALLET_ID)
                    .amount(new BigDecimal("10.0000"))
                    .build();
            txn.setId(TXN_ID);
            txn.setStatus(TransactionStatus.COMPLETED);
            Page<Transaction> page = new PageImpl<>(java.util.List.of(txn));
            when(transactionRepository.findBySenderWalletIdOrReceiverWalletId(
                    eq(SENDER_WALLET_ID), eq(SENDER_WALLET_ID), any(Pageable.class)))
                    .thenReturn(page);

            Page<TransactionResponse> result =
                    service.getTransactionsForCaller(AUTH_HEADER, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(TXN_ID);
        }
    }
}
