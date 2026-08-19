package com.splitexpense.expense.service;

import com.splitexpense.expense.client.GroupClient;
import com.splitexpense.expense.client.dto.ApplyBalancesPayload;
import com.splitexpense.expense.client.dto.BalanceDeltaPayload;
import com.splitexpense.expense.client.dto.GroupBalancesView;
import com.splitexpense.expense.client.dto.GroupView;
import com.splitexpense.expense.dto.request.CreateSettlementRequest;
import com.splitexpense.expense.dto.response.SettlementResponse;
import com.splitexpense.expense.entity.IdempotencyRecord;
import com.splitexpense.expense.entity.Settlement;
import com.splitexpense.expense.entity.SettlementStatus;
import com.splitexpense.expense.event.ExpenseEventPublisher;
import com.splitexpense.expense.exception.GroupOperationException;
import com.splitexpense.expense.exception.GroupServiceUnavailableException;
import com.splitexpense.expense.exception.IdempotencyKeyConflictException;
import com.splitexpense.expense.exception.InsufficientDebtException;
import com.splitexpense.expense.exception.NoSuchGroupException;
import com.splitexpense.expense.exception.NotAGroupMemberException;
import com.splitexpense.expense.exception.NotSettlementPartyException;
import com.splitexpense.expense.exception.OperationInProgressException;
import com.splitexpense.expense.exception.ResourceNotFoundException;
import com.splitexpense.expense.exception.SameUserSettlementException;
import com.splitexpense.expense.exception.SettlementFailedException;
import com.splitexpense.expense.mapper.SettlementMapper;
import com.splitexpense.expense.repository.IdempotencyRecordRepository;
import com.splitexpense.expense.repository.SettlementRepository;
import java.util.List;
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
 * Orchestrates recording one settlement: a single-delta saga running the same idempotency and
 * apply machinery as {@link ExpenseService}, against the same group-service {@code :apply}
 * endpoint.
 *
 * <h2>Who may record a settlement</h2>
 *
 * <p>Either party to the debt — the payer, confirming it went out, or the recipient,
 * confirming it came in — but not an uninvolved third member, even one who belongs to the same
 * group. See {@link NotSettlementPartyException}.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private static final String RETRY_SHORTLY =
            "A request with this Idempotency-Key is already being processed. Retry shortly.";

    private final SettlementRepository settlementRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final GroupClient groupClient;
    private final AuditService auditService;
    private final SettlementMapper settlementMapper;
    private final ObjectMapper objectMapper;
    private final ExpenseEventPublisher expenseEventPublisher;

    public SettlementService(
            SettlementRepository settlementRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            GroupClient groupClient,
            AuditService auditService,
            SettlementMapper settlementMapper,
            ObjectMapper objectMapper,
            ExpenseEventPublisher expenseEventPublisher) {
        this.settlementRepository = settlementRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.groupClient = groupClient;
        this.auditService = auditService;
        this.settlementMapper = settlementMapper;
        this.objectMapper = objectMapper;
        this.expenseEventPublisher = expenseEventPublisher;
    }

    /**
     * Executes (or replays) recording one settlement.
     *
     * @param callerId                  the caller, from a verified JWT
     * @param idempotencyKey            the client's {@code Idempotency-Key} header, required
     * @param request                   group, both parties, amount and optional note
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to authorise
     * @return the recorded settlement
     * @throws SameUserSettlementException     {@code fromUserId} equals {@code toUserId}
     * @throws NoSuchGroupException            the caller is not a member of the named group
     * @throws NotSettlementPartyException     the caller is neither party to the settlement
     * @throws NotAGroupMemberException        either party is not a member of the group
     * @throws InsufficientDebtException       {@code fromUserId} does not currently owe
     *                                         {@code toUserId} at least {@code amount}
     * @throws IdempotencyKeyConflictException the key was already used with a different request
     * @throws OperationInProgressException    no terminal outcome yet for this key
     * @throws SettlementFailedException       group-service refused the delta
     * @throws GroupServiceUnavailableException the apply call was ambiguous
     */
    public SettlementResponse recordSettlement(
            UUID callerId,
            String idempotencyKey,
            CreateSettlementRequest request,
            String callerAuthorizationHeader) {

        if (request.fromUserId().equals(request.toUserId())) {
            throw new SameUserSettlementException("Cannot settle with yourself");
        }
        if (!callerId.equals(request.fromUserId()) && !callerId.equals(request.toUserId())) {
            throw new NotSettlementPartyException(
                    "Only the payer or the recipient may record this settlement");
        }

        GroupView group = groupClient.getGroupForCaller(request.groupId(), callerAuthorizationHeader);
        java.util.Set<UUID> memberIds = group.memberIds();
        if (!memberIds.contains(request.fromUserId()) || !memberIds.contains(request.toUserId())) {
            throw new NotAGroupMemberException(
                    "Both parties to a settlement must be current members of the group");
        }

        // A settlement can only ever confirm a debt that already exists — it must never be
        // able to manufacture one. Checked against a fresh read, not a value cached from
        // earlier in this request, so this reflects whatever the debt graph actually says
        // right now.
        GroupBalancesView balances = groupClient.getBalances(request.groupId(), callerAuthorizationHeader);
        var owed = balances.owed(request.fromUserId(), request.toUserId());
        if (request.amount().compareTo(owed) > 0) {
            throw new InsufficientDebtException(
                    owed.signum() == 0
                            ? "There is no debt to settle between these two members"
                            : ("This settlement is for more than is owed: at most " + owed
                                    + " " + group.currency() + " can be settled here"));
        }

        String requestHash = RequestHasher.hash(callerId + "|" + request.groupId() + "|"
                + request.fromUserId() + "|" + request.toUserId() + "|"
                + request.amount().toPlainString() + "|"
                + java.util.Objects.toString(request.note(), ""));

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        IdempotencyRecord record;
        try {
            record = idempotencyRecordRepository.saveAndFlush(
                    IdempotencyRecord.builder().key(idempotencyKey).requestHash(requestHash).build());
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lost the race to reserve idempotency key {}", idempotencyKey);
            return replay(
                    idempotencyRecordRepository.findById(idempotencyKey)
                            .orElseThrow(() -> new OperationInProgressException(RETRY_SHORTLY)),
                    requestHash);
        }

        return execute(idempotencyKey, request, group.currency(), record);
    }

    /**
     * One page of a group's settlements, newest first.
     *
     * @param groupId                   group to read
     * @param callerAuthorizationHeader the caller's own bearer token, forwarded to authorise
     * @param pageable                  page number, size and sort
     * @return the requested page
     * @throws NoSuchGroupException the caller is not a member of the group
     */
    public Page<SettlementResponse> getGroupSettlements(
            UUID groupId, String callerAuthorizationHeader, Pageable pageable) {
        groupClient.getGroupForCaller(groupId, callerAuthorizationHeader);
        return settlementRepository.findByGroupId(groupId, pageable).map(settlementMapper::toResponse);
    }

    /**
     * Reads one settlement, provided the caller belongs to its group.
     *
     * @param settlementId               settlement to read
     * @param callerAuthorizationHeader  the caller's own bearer token, forwarded to authorise
     * @return the settlement
     * @throws ResourceNotFoundException no such settlement, or the caller is not a member
     */
    public SettlementResponse getSettlement(UUID settlementId, String callerAuthorizationHeader) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement not found: " + settlementId));

        try {
            groupClient.getGroupForCaller(settlement.getGroupId(), callerAuthorizationHeader);
        } catch (NoSuchGroupException notAMember) {
            throw new ResourceNotFoundException("Settlement not found: " + settlementId);
        }

        return settlementMapper.toResponse(settlement);
    }

    private SettlementResponse execute(
            String idempotencyKey,
            CreateSettlementRequest request,
            String currency,
            IdempotencyRecord idempotencyRecord) {

        Settlement settlement = Settlement.builder()
                .groupId(request.groupId())
                .fromUserId(request.fromUserId())
                .toUserId(request.toUserId())
                .amount(request.amount())
                .currency(currency)
                .note(request.note())
                .idempotencyKey(idempotencyKey)
                .build();
        try {
            settlement = settlementRepository.saveAndFlush(settlement);
        } catch (DataIntegrityViolationException raceLost) {
            throw new OperationInProgressException(RETRY_SHORTLY);
        }

        auditService.record(settlement.getId(), "SETTLEMENT_INITIATED", "from=%s to=%s amount=%s"
                .formatted(request.fromUserId(), request.toUserId(), request.amount()));

        try {
            // A BalanceDeltaPayload(debtorId, creditorId, amount) always INCREASES what
            // debtorId owes creditorId — group-service has no notion of a delta that
            // subtracts. A settlement must do the opposite of that: fromUserId just paid
            // toUserId in the real world, so fromUserId's debt to toUserId needs to go
            // DOWN. The only delta that decreases "fromUserId owes toUserId" is one that
            // increases the reverse relation — so the debtor/creditor here are toUserId
            // and fromUserId, swapped from the settlement's own field names. Getting this
            // backwards doesn't fail loudly: it silently doubles the debt instead of
            // clearing it, which is exactly what happened here before this comment existed.
            groupClient.apply(request.groupId(), new ApplyBalancesPayload(
                    settlement.getId().toString(), "SETTLEMENT", request.note(),
                    List.of(new BalanceDeltaPayload(
                            request.toUserId(), request.fromUserId(), request.amount()))));
        } catch (GroupOperationException ex) {
            settlement = markTerminal(settlement, SettlementStatus.FAILED, ex.getMessage());
            auditService.record(settlement.getId(), "SETTLEMENT_APPLY_REFUSED", ex.getMessage());
            throw new SettlementFailedException(ex.getMessage());
        } catch (GroupServiceUnavailableException ex) {
            // Left INITIATED, exactly as ExpenseService leaves an ambiguous apply: the
            // reversal is idempotent by this settlement's id, so a later retry converges
            // regardless of whether this attempt actually landed.
            auditService.record(settlement.getId(), "SETTLEMENT_APPLY_AMBIGUOUS", ex.getMessage());
            throw ex;
        }

        settlement = markTerminal(settlement, SettlementStatus.COMPLETED, null);
        auditService.record(settlement.getId(), "SETTLEMENT_COMPLETED", "amount=" + request.amount());

        expenseEventPublisher.publishSettlementRecorded(
                settlement.getId(), settlement.getGroupId(), settlement.getFromUserId(),
                settlement.getToUserId(), settlement.getAmount(), settlement.getCurrency());

        SettlementResponse response = settlementMapper.toResponse(settlement);
        cacheSuccess(idempotencyRecord, response);
        return response;
    }

    private SettlementResponse replay(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException(
                    "This Idempotency-Key was already used with a different request");
        }
        if (record.getResponseBody() != null) {
            return deserializeResponse(record.getResponseBody());
        }

        Settlement settlement = settlementRepository.findByIdempotencyKey(record.getKey())
                .orElseThrow(() -> new OperationInProgressException(RETRY_SHORTLY));

        return switch (settlement.getStatus()) {
            case COMPLETED -> settlementMapper.toResponse(settlement);
            case FAILED -> throw new SettlementFailedException(settlement.getFailureReason());
            case INITIATED -> throw new OperationInProgressException(RETRY_SHORTLY);
        };
    }

    private Settlement markTerminal(Settlement settlement, SettlementStatus status, String failureReason) {
        settlement.setStatus(status);
        settlement.setFailureReason(failureReason);
        return settlementRepository.save(settlement);
    }

    private void cacheSuccess(IdempotencyRecord record, SettlementResponse response) {
        try {
            record.setResponseBody(objectMapper.writeValueAsString(response));
            record.setHttpStatus(HttpStatus.OK.value());
            idempotencyRecordRepository.save(record);
        } catch (RuntimeException ex) {
            log.warn("Could not cache the idempotent response for key {}", record.getKey(), ex);
        }
    }

    private SettlementResponse deserializeResponse(String json) {
        return objectMapper.readValue(json, SettlementResponse.class);
    }
}
