package com.splitexpense.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitexpense.expense.client.GroupClient;
import com.splitexpense.expense.client.dto.ApplyBalancesPayload;
import com.splitexpense.expense.client.dto.ApplyBalancesResult;
import com.splitexpense.expense.client.dto.GroupMemberView;
import com.splitexpense.expense.client.dto.GroupView;
import com.splitexpense.expense.dto.request.CreateExpenseRequest;
import com.splitexpense.expense.dto.request.SplitParticipantRequest;
import com.splitexpense.expense.dto.response.ExpenseResponse;
import com.splitexpense.expense.entity.Expense;
import com.splitexpense.expense.entity.ExpenseStatus;
import com.splitexpense.expense.entity.IdempotencyRecord;
import com.splitexpense.expense.entity.SplitType;
import com.splitexpense.expense.event.ExpenseEventPublisher;
import com.splitexpense.expense.exception.ExpenseFailedException;
import com.splitexpense.expense.exception.ExpenseNotVoidableException;
import com.splitexpense.expense.exception.GroupOperationException;
import com.splitexpense.expense.exception.GroupServiceUnavailableException;
import com.splitexpense.expense.exception.IdempotencyKeyConflictException;
import com.splitexpense.expense.exception.InvalidSplitException;
import com.splitexpense.expense.exception.NotAGroupMemberException;
import com.splitexpense.expense.exception.OperationInProgressException;
import com.splitexpense.expense.mapper.ExpenseMapper;
import com.splitexpense.expense.repository.ExpenseRepository;
import com.splitexpense.expense.repository.ExpenseSplitRepository;
import com.splitexpense.expense.repository.IdempotencyRecordRepository;
import com.splitexpense.expense.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the expense saga, with every collaborator mocked except {@link ExpenseMapper}
 * and the real {@link com.splitexpense.expense.domain.SplitCalculator} — both pure,
 * dependency-free logic that is more useful exercised for real than replaced with a mock that
 * would just echo back whatever the test told it to.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

    private static final String KEY = "idem-key-1";
    private static final String AUTH_HEADER = "Bearer caller-token";
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID PAYER_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final AuthenticatedUser CALLER =
            new AuthenticatedUser(CALLER_ID, "ada@splitexpense.io", "USER");

    @Mock private ExpenseRepository expenseRepository;
    @Mock private ExpenseSplitRepository expenseSplitRepository;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private GroupClient groupClient;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ExpenseEventPublisher expenseEventPublisher;

    private ExpenseService service;

    @BeforeEach
    void setUp() {
        service = new ExpenseService(
                expenseRepository,
                expenseSplitRepository,
                idempotencyRecordRepository,
                groupClient,
                auditService,
                new ExpenseMapper(),
                objectMapper,
                expenseEventPublisher);

        // markTerminal relies on save()'s return value carrying the bumped @Version forward;
        // echoing the argument back keeps that contract true for tests. lenient: not every
        // test reaches a save() call.
        lenient().when(expenseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static GroupView group(UUID... memberIds) {
        List<GroupMemberView> members = java.util.Arrays.stream(memberIds)
                .map(id -> new GroupMemberView(id, "MEMBER", Instant.now()))
                .toList();
        return new GroupView(
                GROUP_ID, "Flat", null, "INR", PAYER_ID, "ACTIVE", members,
                Instant.now(), Instant.now());
    }

    private static CreateExpenseRequest request() {
        return new CreateExpenseRequest(
                GROUP_ID, PAYER_ID, new BigDecimal("100.0000"), "Dinner", SplitType.EQUAL,
                List.of(
                        new SplitParticipantRequest(PAYER_ID, null),
                        new SplitParticipantRequest(PARTICIPANT_ID, null)));
    }

    private void givenNoExistingIdempotencyRecord() {
        when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenExpenseIsPersisted() {
        when(expenseRepository.saveAndFlush(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Nested
    @DisplayName("creating an expense")
    class CreateExpense {

        @Test
        @DisplayName("applies deltas from every non-payer participant to the payer")
        void appliesDeltasToThePayer() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            givenNoExistingIdempotencyRecord();
            givenExpenseIsPersisted();
            when(groupClient.apply(eq(GROUP_ID), any()))
                    .thenReturn(new ApplyBalancesResult(GROUP_ID, "ref", true, null));

            ExpenseResponse response = service.createExpense(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response.status()).isEqualTo(ExpenseStatus.COMPLETED);
            assertThat(response.amount()).isEqualByComparingTo("100.0000");

            ArgumentCaptor<ApplyBalancesPayload> captor =
                    ArgumentCaptor.forClass(ApplyBalancesPayload.class);
            verify(groupClient).apply(eq(GROUP_ID), captor.capture());

            ApplyBalancesPayload payload = captor.getValue();
            assertThat(payload.reason()).isEqualTo("EXPENSE");
            assertThat(payload.deltas()).hasSize(1);
            assertThat(payload.deltas().get(0).debtorId()).isEqualTo(PARTICIPANT_ID);
            assertThat(payload.deltas().get(0).creditorId()).isEqualTo(PAYER_ID);
            assertThat(payload.deltas().get(0).amount()).isEqualByComparingTo("50.0000");
        }

        @Test
        @DisplayName("skips the group-service call entirely when the payer covers themselves alone")
        void singleParticipantPayerSkipsApply() {
            CreateExpenseRequest soloRequest = new CreateExpenseRequest(
                    GROUP_ID, PAYER_ID, new BigDecimal("40.0000"), "Coffee", SplitType.EQUAL,
                    List.of(new SplitParticipantRequest(PAYER_ID, null)));

            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group(PAYER_ID));
            givenNoExistingIdempotencyRecord();
            givenExpenseIsPersisted();

            ExpenseResponse response = service.createExpense(CALLER, KEY, soloRequest, AUTH_HEADER);

            assertThat(response.status()).isEqualTo(ExpenseStatus.COMPLETED);
            verify(groupClient, never()).apply(any(), any());
        }

        @Test
        @DisplayName("rejects a payer who is not a member of the group, before any local write")
        void rejectsNonMemberPayer() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PARTICIPANT_ID)); // payer absent

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(NotAGroupMemberException.class);

            verify(idempotencyRecordRepository, never()).saveAndFlush(any());
            verify(expenseRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a participant who is not a member of the group")
        void rejectsNonMemberParticipant() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID)); // participant absent

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(NotAGroupMemberException.class);
        }

        @Test
        @DisplayName("rejects a split whose participants describe an impossible division")
        void rejectsInvalidSplit() {
            CreateExpenseRequest duplicateParticipant = new CreateExpenseRequest(
                    GROUP_ID, PAYER_ID, new BigDecimal("100.0000"), "Dinner", SplitType.EQUAL,
                    List.of(
                            new SplitParticipantRequest(PARTICIPANT_ID, null),
                            new SplitParticipantRequest(PARTICIPANT_ID, null)));

            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));

            assertThatThrownBy(() ->
                    service.createExpense(CALLER, KEY, duplicateParticipant, AUTH_HEADER))
                    .isInstanceOf(InvalidSplitException.class);
        }

        @Test
        @DisplayName("marks the expense FAILED when group-service refuses the deltas")
        void marksFailedOnRefusal() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            givenNoExistingIdempotencyRecord();
            givenExpenseIsPersisted();
            when(groupClient.apply(eq(GROUP_ID), any()))
                    .thenThrow(new GroupOperationException(409, "Group is archived"));

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(ExpenseFailedException.class)
                    .hasMessageContaining("archived");

            ArgumentCaptor<Expense> captor = ArgumentCaptor.forClass(Expense.class);
            verify(expenseRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ExpenseStatus.FAILED);
        }

        @Test
        @DisplayName("leaves the expense INITIATED when the apply is ambiguous")
        void leavesInitiatedOnAmbiguity() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            givenNoExistingIdempotencyRecord();
            givenExpenseIsPersisted();
            when(groupClient.apply(eq(GROUP_ID), any()))
                    .thenThrow(new GroupServiceUnavailableException("timeout"));

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(GroupServiceUnavailableException.class);

            // No terminal save at all: the row a caller would read back is still INITIATED,
            // exactly as saveAndFlush left it.
            verify(expenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("replays a cached response without touching group-service again")
        void replaysCachedResponse() {
            IdempotencyRecord cached = IdempotencyRecord.builder()
                    .key(KEY)
                    .requestHash(hashOf(request()))
                    .responseBody("{}")
                    .build();
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.of(cached));

            ExpenseResponse cannedResponse = new ExpenseResponse(
                    UUID.randomUUID(), GROUP_ID, PAYER_ID, new BigDecimal("100.0000"), "INR",
                    "Dinner", SplitType.EQUAL, ExpenseStatus.COMPLETED, null, List.of(),
                    Instant.now(), Instant.now());
            when(objectMapper.readValue("{}", ExpenseResponse.class)).thenReturn(cannedResponse);

            ExpenseResponse response = service.createExpense(CALLER, KEY, request(), AUTH_HEADER);

            assertThat(response).isSameAs(cannedResponse);
            verify(groupClient, never()).apply(any(), any());
            verify(expenseRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects a key reused with a different request body")
        void rejectsConflictingReplay() {
            IdempotencyRecord cached = IdempotencyRecord.builder()
                    .key(KEY)
                    .requestHash("a-completely-different-hash")
                    .build();
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.of(cached));

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        }

        @Test
        @DisplayName("treats losing the reservation race as in-progress when no record is found")
        void reservationRaceWithoutRecordIsInProgress() {
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER))
                    .thenReturn(group(PAYER_ID, PARTICIPANT_ID));
            when(idempotencyRecordRepository.findById(KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(idempotencyRecordRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> service.createExpense(CALLER, KEY, request(), AUTH_HEADER))
                    .isInstanceOf(OperationInProgressException.class);
        }
    }

    @Nested
    @DisplayName("voiding an expense")
    class VoidExpense {

        private Expense completedExpense(UUID id) {
            return Expense.builder()
                    .id(id)
                    .groupId(GROUP_ID)
                    .payerUserId(PAYER_ID)
                    .amount(new BigDecimal("100.0000"))
                    .description("Dinner")
                    .splitType(SplitType.EQUAL)
                    .status(ExpenseStatus.COMPLETED)
                    .idempotencyKey("create-key")
                    .build();
        }

        /**
         * Pins the bug a live run against the real stack caught: voiding an expense
         * flips its status to VOIDED, and a genuine retry of that same call — same
         * expense, same key, after a dropped connection — must be answered from the
         * cached response. Checking the expense's <em>current</em> status before
         * checking whether this key was already used made a replay indistinguishable
         * from a stranger trying to void an already-voided expense under a fresh key,
         * and rejected both with 409 — so a successful void's own retry could never
         * observe its own success.
         */
        @Test
        @DisplayName("a repeat call with the same key replays the cached result, not a 409")
        void replayAfterVoidReturnsCachedResponse() {
            UUID expenseId = UUID.randomUUID();
            // The expense the mock repository hands back is queried fresh on every call,
            // exactly like Hibernate would after the first voidExpense's own commit — its
            // status already reads VOIDED, which is what makes the ordering bug reachable.
            Expense alreadyVoided = completedExpense(expenseId);
            alreadyVoided.setStatus(ExpenseStatus.VOIDED);

            when(expenseRepository.findById(expenseId)).thenReturn(Optional.of(alreadyVoided));
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group(PAYER_ID));
            when(expenseSplitRepository.findByExpenseId(expenseId)).thenReturn(List.of());

            // RequestHasher is package-private, reachable directly since this test lives in
            // the same package — no need to re-derive its algorithm by hand.
            String cachedJson = "{\"id\":\"" + expenseId + "\"}";
            IdempotencyRecord cached = IdempotencyRecord.builder()
                    .key(KEY)
                    .requestHash(RequestHasher.hash("void|" + expenseId))
                    .responseBody(cachedJson)
                    .build();
            when(idempotencyRecordRepository.findById(KEY)).thenReturn(Optional.of(cached));

            ExpenseResponse canned = new ExpenseResponse(
                    expenseId, GROUP_ID, PAYER_ID, new BigDecimal("100.0000"), "INR", "Dinner",
                    SplitType.EQUAL, ExpenseStatus.VOIDED, null, List.of(), Instant.now(), Instant.now());
            when(objectMapper.readValue(cachedJson, ExpenseResponse.class)).thenReturn(canned);

            ExpenseResponse response =
                    service.voidExpense(expenseId, PAYER_ID, KEY, AUTH_HEADER);

            assertThat(response).isSameAs(canned);
            verify(groupClient, never()).apply(any(), any());
        }

        @Test
        @DisplayName("a fresh key against an already-voided expense is rejected, not replayed")
        void freshKeyAgainstVoidedExpenseIsRejected() {
            UUID expenseId = UUID.randomUUID();
            Expense alreadyVoided = completedExpense(expenseId);
            alreadyVoided.setStatus(ExpenseStatus.VOIDED);

            when(expenseRepository.findById(expenseId)).thenReturn(Optional.of(alreadyVoided));
            when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group(PAYER_ID));
            when(expenseSplitRepository.findByExpenseId(expenseId)).thenReturn(List.of());
            when(idempotencyRecordRepository.findById("a-never-used-key")).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.voidExpense(expenseId, PAYER_ID, "a-never-used-key", AUTH_HEADER))
                    .isInstanceOf(ExpenseNotVoidableException.class);

            verify(idempotencyRecordRepository, never()).saveAndFlush(any());
        }
    }

    /**
     * Mirrors {@code ExpenseService#hashRequest} exactly, so fixtures can produce a hash that
     * agrees with what the service itself would compute — the private method itself is not
     * exposed for tests to call.
     */
    private static String hashOf(CreateExpenseRequest request) {
        try {
            String participants = request.participants().stream()
                    .map(p -> p.userId() + "=" + (p.value() == null ? "" : p.value().toPlainString()))
                    .sorted()
                    .reduce("", (a, b) -> a + ";" + b);

            String canonical = CALLER_ID + "|" + request.groupId() + "|" + request.payerUserId() + "|"
                    + request.amount().toPlainString() + "|" + request.splitType() + "|"
                    + java.util.Objects.toString(request.description(), "") + "|" + participants;

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
