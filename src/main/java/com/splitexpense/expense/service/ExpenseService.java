package com.splitexpense.expense.service;

import com.splitexpense.expense.client.GroupClient;
import com.splitexpense.expense.client.dto.ApplyBalancesPayload;
import com.splitexpense.expense.client.dto.ApplyBalancesResult;
import com.splitexpense.expense.client.dto.BalanceDeltaPayload;
import com.splitexpense.expense.client.dto.GroupView;
import com.splitexpense.expense.domain.SplitCalculator;
import com.splitexpense.expense.dto.request.CreateExpenseRequest;
import com.splitexpense.expense.dto.request.SplitParticipantRequest;
import com.splitexpense.expense.dto.response.ExpenseResponse;
import com.splitexpense.expense.entity.Expense;
import com.splitexpense.expense.entity.ExpenseSplit;
import com.splitexpense.expense.entity.ExpenseStatus;
import com.splitexpense.expense.entity.IdempotencyRecord;
import com.splitexpense.expense.event.ExpenseEventPublisher;
import com.splitexpense.expense.exception.ExpenseFailedException;
import com.splitexpense.expense.exception.ExpenseNotVoidableException;
import com.splitexpense.expense.exception.GroupOperationException;
import com.splitexpense.expense.exception.GroupServiceUnavailableException;
import com.splitexpense.expense.exception.IdempotencyKeyConflictException;
import com.splitexpense.expense.exception.NoSuchGroupException;
import com.splitexpense.expense.exception.NotAGroupMemberException;
import com.splitexpense.expense.exception.NotExpenseParticipantException;
import com.splitexpense.expense.exception.OperationInProgressException;
import com.splitexpense.expense.exception.ResourceNotFoundException;
import com.splitexpense.expense.mapper.ExpenseMapper;
import com.splitexpense.expense.repository.ExpenseRepository;
import com.splitexpense.expense.repository.ExpenseSplitRepository;
import com.splitexpense.expense.repository.IdempotencyRecordRepository;
import com.splitexpense.expense.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Orchestrates recording and voiding one expense — the saga described by the tables in
 * {@code V1__create_expenses_splits_and_settlements.sql}.
 *
 * <h2>Why this saga has no compensation logic</h2>
 *
 * <p>The transfer saga this service's predecessor ran needed a compensating credit because a
 * transfer was two independent remote calls — debit, then credit — with a window between them
 * in which money had moved on one side but not the other. An expense applies every one of its
 * deltas in a <strong>single</strong> call to group-service's {@code :apply} endpoint, which is
 * atomic (every delta commits together or none do) and idempotent by this expense's own id. So
 * an interrupted apply is never partially applied, and a retry of the same expense converges
 * on the correct state whether or not the original call landed. There is no intermediate state
 * to be stranded in, and therefore nothing to compensate for. See {@link ExpenseStatus}.
 *
 * <h2>Why no {@code @Transactional} spans this class</h2>
 *
 * <p>An expense is several short local writes separated by remote calls to group-service. Each
 * local write below is its own short, already-committed transaction, and the group-service
 * calls happen entirely outside any of them — the same reasoning this service's own
 * {@code application.yml} Hikari comment gives.
 */
@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private static final String RETRY_SHORTLY =
            "A request with this Idempotency-Key is already being processed. Retry shortly.";

    private final ExpenseRepository expenseRepository;
    private final ExpenseSplitRepository expenseSplitRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final GroupClient groupClient;
    private final AuditService auditService;
    private final ExpenseMapper expenseMapper;
    private final ObjectMapper objectMapper;
    private final ExpenseEventPublisher expenseEventPublisher;

    public ExpenseService(
            ExpenseRepository expenseRepository,
            ExpenseSplitRepository expenseSplitRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            GroupClient groupClient,
            AuditService auditService,
            ExpenseMapper expenseMapper,
            ObjectMapper objectMapper,
            ExpenseEventPublisher expenseEventPublisher) {
        this.expenseRepository = expenseRepository;
        this.expenseSplitRepository = expenseSplitRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.groupClient = groupClient;
        this.auditService = auditService;
        this.expenseMapper = expenseMapper;
        this.objectMapper = objectMapper;
        this.expenseEventPublisher = expenseEventPublisher;
    }

    /**
     * Executes (or replays) recording one expense.
     *
     * @param caller                    the verified caller
     * @param idempotencyKey            the client's {@code Idempotency-Key} header, required
     * @param request                   group, payer, amount, split type and participants
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded verbatim to
     *                                   resolve and authorise against the group — never the
     *                                   service account's
     * @return the recorded expense
     * @throws NoSuchGroupException            the caller is not a member of the named group
     * @throws NotAGroupMemberException        the payer, or a participant, is not a member
     * @throws com.splitexpense.expense.exception.InvalidSplitException the participants or
     *                                         their split inputs do not describe a valid split
     * @throws IdempotencyKeyConflictException the key was already used with a different request
     * @throws OperationInProgressException    no terminal outcome yet for this key
     * @throws ExpenseFailedException          group-service refused the deltas
     * @throws GroupServiceUnavailableException the apply call was ambiguous
     */
    public ExpenseResponse createExpense(
            AuthenticatedUser caller,
            String idempotencyKey,
            CreateExpenseRequest request,
            String callerAuthorizationHeader) {

        GroupView group = groupClient.getGroupForCaller(request.groupId(), callerAuthorizationHeader);
        requireAllMembers(group, request);

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
            log.debug("Lost the race to reserve idempotency key {}", idempotencyKey);
            return replay(
                    idempotencyRecordRepository.findById(idempotencyKey)
                            .orElseThrow(() -> new OperationInProgressException(RETRY_SHORTLY)),
                    requestHash);
        }

        return execute(idempotencyKey, request, group, record);
    }

    /**
     * One page of a group's expenses, newest first.
     *
     * @param groupId                   group to read
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to authorise
     * @param pageable                  page number, size and sort
     * @return the requested page
     * @throws NoSuchGroupException the caller is not a member of the group
     */
    public Page<ExpenseResponse> getGroupExpenses(
            UUID groupId, String callerAuthorizationHeader, Pageable pageable) {
        groupClient.getGroupForCaller(groupId, callerAuthorizationHeader);
        return expenseRepository.findByGroupId(groupId, pageable)
                .map(expense -> expenseMapper.toResponse(
                        expense, expenseSplitRepository.findByExpenseId(expense.getId())));
    }

    /**
     * One page of the expenses the caller paid, newest first.
     *
     * @param payerUserId the caller, from a verified JWT
     * @param pageable    page number, size and sort
     * @return the requested page
     */
    public Page<ExpenseResponse> getExpensesForPayer(UUID payerUserId, Pageable pageable) {
        return expenseRepository.findByPayerUserId(payerUserId, pageable)
                .map(expense -> expenseMapper.toResponse(
                        expense, expenseSplitRepository.findByExpenseId(expense.getId())));
    }

    /**
     * Reads one expense, provided the caller belongs to its group.
     *
     * @param expenseId                 expense to read
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to authorise
     * @return the expense and its splits
     * @throws ResourceNotFoundException no such expense, or the caller is not a member of its
     *                                   group — the two are indistinguishable to the caller
     */
    public ExpenseResponse getExpense(UUID expenseId, String callerAuthorizationHeader) {
        Expense expense = requireExpenseVisibleTo(expenseId, callerAuthorizationHeader);
        return expenseMapper.toResponse(expense, expenseSplitRepository.findByExpenseId(expenseId));
    }

    /**
     * Voids a completed expense: applies the exact inverse of its original deltas, so the
     * group's balances end up exactly as if the expense had never been recorded.
     *
     * @param expenseId                 expense to void
     * @param callerId                  the caller, from a verified JWT
     * @param idempotencyKey            the client's {@code Idempotency-Key} header, required
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to authorise
     * @return the voided expense
     * @throws ResourceNotFoundException     no such expense, or the caller is not a member
     * @throws NotExpenseParticipantException the caller is neither the payer nor a participant
     * @throws ExpenseNotVoidableException   the expense is not currently {@code COMPLETED}
     * @throws IdempotencyKeyConflictException the key was already used with a different request
     * @throws OperationInProgressException  no terminal outcome yet for this key
     * @throws ExpenseFailedException        group-service refused the reversing deltas
     */
    public ExpenseResponse voidExpense(
            UUID expenseId, UUID callerId, String idempotencyKey, String callerAuthorizationHeader) {

        Expense expense = requireExpenseVisibleTo(expenseId, callerAuthorizationHeader);
        List<ExpenseSplit> splits = expenseSplitRepository.findByExpenseId(expenseId);

        boolean isParticipant = expense.getPayerUserId().equals(callerId)
                || splits.stream().anyMatch(split -> split.getUserId().equals(callerId));
        if (!isParticipant) {
            throw new NotExpenseParticipantException(
                    "Only the payer or a participant may void this expense");
        }

        String requestHash = RequestHasher.hash("void|" + expenseId);

        // The replay check runs BEFORE the status check, and this order is load-bearing.
        // Voiding this exact expense under this exact key already flipped its status to
        // VOIDED once — a genuine retry of that same call (a dropped connection, a timeout)
        // must be answered from the cached response, not re-evaluated against the new
        // "already voided" status the first call itself produced. Checking status first
        // would make every replay indistinguishable from someone else trying to void an
        // already-voided expense under a fresh key, and reject both alike with 409 — which
        // is exactly the bug this comment replaced: a successful void's own idempotent
        // retry could never observe its own success.
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replayVoid(existing.get(), requestHash);
        }

        // Only reached for a key that has never been used against this expense. Checked
        // before the key is reserved: reserving first and rejecting afterwards would leave
        // a row in idempotency_records with no response ever cached — permanently
        // OperationInProgressException on every future retry of this same key, since
        // nothing would ever call #cacheSuccess for it.
        if (expense.getStatus() != ExpenseStatus.COMPLETED) {
            throw new ExpenseNotVoidableException(
                    "Only a COMPLETED expense may be voided; this one is " + expense.getStatus());
        }

        IdempotencyRecord record;
        try {
            record = idempotencyRecordRepository.saveAndFlush(
                    IdempotencyRecord.builder().key(idempotencyKey).requestHash(requestHash).build());
        } catch (DataIntegrityViolationException raceLost) {
            return replayVoid(
                    idempotencyRecordRepository.findById(idempotencyKey)
                            .orElseThrow(() -> new OperationInProgressException(RETRY_SHORTLY)),
                    requestHash);
        }

        return executeVoid(expense, splits, record);
    }

    private ExpenseResponse execute(
            String idempotencyKey,
            CreateExpenseRequest request,
            GroupView group,
            IdempotencyRecord idempotencyRecord) {

        Map<UUID, BigDecimal> shares = computeShares(request);

        Expense expense = Expense.builder()
                .groupId(request.groupId())
                .payerUserId(request.payerUserId())
                .amount(request.amount())
                .currency(group.currency())
                .description(request.description())
                .splitType(request.splitType())
                .idempotencyKey(idempotencyKey)
                .build();
        try {
            expense = expenseRepository.saveAndFlush(expense);
        } catch (DataIntegrityViolationException raceLost) {
            // uq_expenses_idempotency_key is the true arbiter of this key. Reaching this catch
            // despite having already reserved the idempotency_records row above would mean two
            // requests raced ahead of that reservation; treat it the same way.
            throw new OperationInProgressException(RETRY_SHORTLY);
        }

        List<ExpenseSplit> splits = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> share : shares.entrySet()) {
            splits.add(ExpenseSplit.builder()
                    .expenseId(expense.getId())
                    .userId(share.getKey())
                    .shareAmount(share.getValue())
                    .build());
        }
        expenseSplitRepository.saveAll(splits);

        auditService.record(expense.getId(), "EXPENSE_INITIATED",
                "group=%s payer=%s amount=%s splitType=%s".formatted(
                        request.groupId(), request.payerUserId(), request.amount(), request.splitType()));

        List<BalanceDeltaPayload> deltas = buildDeltas(request.payerUserId(), shares);

        if (!deltas.isEmpty()) {
            try {
                groupClient.apply(request.groupId(), new ApplyBalancesPayload(
                        expense.getId().toString(), "EXPENSE", request.description(), deltas));
            } catch (GroupOperationException ex) {
                expense = markTerminal(expense, ExpenseStatus.FAILED, ex.getMessage());
                auditService.record(expense.getId(), "EXPENSE_APPLY_REFUSED", ex.getMessage());
                throw new ExpenseFailedException(ex.getMessage());
            } catch (GroupServiceUnavailableException ex) {
                // Left INITIATED: the apply is idempotent by this expense's id, so a later
                // retry — of this same request, or a scheduled sweep — converges regardless
                // of whether this attempt actually landed. See the class javadoc.
                auditService.record(expense.getId(), "EXPENSE_APPLY_AMBIGUOUS", ex.getMessage());
                throw ex;
            }
        }

        expense = markTerminal(expense, ExpenseStatus.COMPLETED, null);
        auditService.record(expense.getId(), "EXPENSE_COMPLETED", "amount=" + request.amount());

        Set<UUID> participantIds = new LinkedHashSet<>(shares.keySet());
        expenseEventPublisher.publishExpenseCreated(
                expense.getId(), expense.getGroupId(), expense.getPayerUserId(),
                List.copyOf(participantIds), expense.getAmount(), expense.getCurrency(),
                expense.getDescription());

        ExpenseResponse response = expenseMapper.toResponse(expense, splits);
        cacheSuccess(idempotencyRecord, response);
        return response;
    }

    private ExpenseResponse executeVoid(
            Expense expense, List<ExpenseSplit> splits, IdempotencyRecord idempotencyRecord) {

        // The exact inverse of the original: same pairs, debtor and creditor swapped, so the
        // net effect on the group's balances cancels to zero.
        List<BalanceDeltaPayload> reversingDeltas = new ArrayList<>();
        for (ExpenseSplit split : splits) {
            if (split.getUserId().equals(expense.getPayerUserId())
                    || split.getShareAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            reversingDeltas.add(new BalanceDeltaPayload(
                    expense.getPayerUserId(), split.getUserId(), split.getShareAmount()));
        }

        if (!reversingDeltas.isEmpty()) {
            try {
                groupClient.apply(expense.getGroupId(), new ApplyBalancesPayload(
                        expense.getId() + "-void", "EXPENSE_VOID", expense.getDescription(),
                        reversingDeltas));
            } catch (GroupOperationException ex) {
                auditService.record(expense.getId(), "EXPENSE_VOID_REFUSED", ex.getMessage());
                throw new ExpenseFailedException(ex.getMessage());
            } catch (GroupServiceUnavailableException ex) {
                // expense.status stays COMPLETED: the void did not observably happen, and a
                // retry under the same key is safe because the reversal is idempotent by its
                // own reference.
                auditService.record(expense.getId(), "EXPENSE_VOID_AMBIGUOUS", ex.getMessage());
                throw ex;
            }
        }

        expense = markTerminal(expense, ExpenseStatus.VOIDED, null);
        auditService.record(expense.getId(), "EXPENSE_VOIDED", "amount=" + expense.getAmount());

        Set<UUID> participantIds = splits.stream().map(ExpenseSplit::getUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        participantIds.add(expense.getPayerUserId());
        expenseEventPublisher.publishExpenseVoided(
                expense.getId(), expense.getGroupId(), List.copyOf(participantIds),
                expense.getAmount(), expense.getCurrency(), expense.getDescription());

        ExpenseResponse response = expenseMapper.toResponse(expense, splits);
        cacheSuccess(idempotencyRecord, response);
        return response;
    }

    /**
     * Turns the split-computation shares into the deltas group-service needs to apply: every
     * non-payer participant with a non-zero share owes the payer that share. The payer's own
     * row, and any zero share, is excluded — nobody owes themselves, and group-service's own
     * {@code BalanceDeltaRequest} rejects an amount of exactly zero.
     */
    private List<BalanceDeltaPayload> buildDeltas(UUID payerUserId, Map<UUID, BigDecimal> shares) {
        List<BalanceDeltaPayload> deltas = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> share : shares.entrySet()) {
            if (share.getKey().equals(payerUserId) || share.getValue().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            deltas.add(new BalanceDeltaPayload(share.getKey(), payerUserId, share.getValue()));
        }
        return deltas;
    }

    private Map<UUID, BigDecimal> computeShares(CreateExpenseRequest request) {
        List<SplitCalculator.ParticipantInput> inputs = request.participants().stream()
                .map(p -> new SplitCalculator.ParticipantInput(p.userId(), p.value()))
                .toList();
        return SplitCalculator.computeShares(request.splitType(), request.amount(), inputs);
    }

    /**
     * @throws NotAGroupMemberException the payer, or any participant, is not a current member
     */
    private void requireAllMembers(GroupView group, CreateExpenseRequest request) {
        Set<UUID> memberIds = group.memberIds();
        if (!memberIds.contains(request.payerUserId())) {
            throw new NotAGroupMemberException(
                    "The payer must be a current member of the group");
        }
        for (SplitParticipantRequest participant : request.participants()) {
            if (!memberIds.contains(participant.userId())) {
                throw new NotAGroupMemberException(
                        "Every participant must be a current member of the group");
            }
        }
    }

    private ExpenseResponse replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "This Idempotency-Key was already used with a different request");
        }
        if (record.getResponseBody() != null) {
            return deserializeResponse(record.getResponseBody());
        }

        Expense expense = expenseRepository.findByIdempotencyKey(record.getKey())
                .orElseThrow(() -> new OperationInProgressException(RETRY_SHORTLY));

        return switch (expense.getStatus()) {
            case COMPLETED, VOIDED -> expenseMapper.toResponse(
                    expense, expenseSplitRepository.findByExpenseId(expense.getId()));
            case FAILED -> throw new ExpenseFailedException(expense.getFailureReason());
            case INITIATED -> throw new OperationInProgressException(RETRY_SHORTLY);
        };
    }

    private ExpenseResponse replayVoid(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "This Idempotency-Key was already used with a different request");
        }
        if (record.getResponseBody() != null) {
            return deserializeResponse(record.getResponseBody());
        }
        throw new OperationInProgressException(RETRY_SHORTLY);
    }

    /**
     * Loads an expense, but only if the caller belongs to its group.
     *
     * @throws ResourceNotFoundException no such expense, or the caller is not a member
     */
    private Expense requireExpenseVisibleTo(UUID expenseId, String callerAuthorizationHeader) {
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + expenseId));

        try {
            groupClient.getGroupForCaller(expense.getGroupId(), callerAuthorizationHeader);
        } catch (NoSuchGroupException notAMember) {
            // Reframed as the expense's own not-found: confirming an expense exists in a
            // group the caller cannot see is already more than they should learn.
            throw new ResourceNotFoundException("Expense not found: " + expenseId);
        }

        return expense;
    }

    /**
     * Each {@code save} here is its own already-committed transaction (see the class javadoc),
     * so by the time it returns the entity is detached and {@code save} took the
     * {@code merge()} path — which returns a distinct, newly-managed instance carrying the
     * bumped {@code @Version}. Callers MUST use the returned entity for any further save.
     */
    private Expense markTerminal(Expense expense, ExpenseStatus status, String failureReason) {
        expense.setStatus(status);
        expense.setFailureReason(failureReason);
        return expenseRepository.save(expense);
    }

    private void cacheSuccess(IdempotencyRecord record, ExpenseResponse response) {
        try {
            record.setResponseBody(objectMapper.writeValueAsString(response));
            record.setHttpStatus(HttpStatus.OK.value());
            idempotencyRecordRepository.save(record);
        } catch (RuntimeException ex) {
            // Losing the cached replay does not lose the expense: the expense row is still
            // the source of truth, and #replay rebuilds the response from it.
            log.warn("Could not cache the idempotent response for key {}", record.getKey(), ex);
        }
    }

    private ExpenseResponse deserializeResponse(String json) {
        return objectMapper.readValue(json, ExpenseResponse.class);
    }

    private String hashRequest(UUID callerId, CreateExpenseRequest request) {
        String participants = request.participants().stream()
                .map(p -> p.userId() + "=" + (p.value() == null ? "" : p.value().toPlainString()))
                .sorted()
                .reduce("", (a, b) -> a + ";" + b);

        String canonical = callerId + "|" + request.groupId() + "|" + request.payerUserId() + "|"
                + request.amount().toPlainString() + "|" + request.splitType() + "|"
                + java.util.Objects.toString(request.description(), "") + "|" + participants;

        return RequestHasher.hash(canonical);
    }
}
