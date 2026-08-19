package com.splitexpense.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.splitexpense.expense.client.GroupClient;
import com.splitexpense.expense.client.dto.ApplyBalancesPayload;
import com.splitexpense.expense.client.dto.ApplyBalancesResult;
import com.splitexpense.expense.client.dto.GroupBalancesView;
import com.splitexpense.expense.client.dto.GroupMemberView;
import com.splitexpense.expense.client.dto.GroupView;
import com.splitexpense.expense.client.dto.PairBalanceView;
import com.splitexpense.expense.dto.request.CreateSettlementRequest;
import com.splitexpense.expense.entity.Settlement;
import com.splitexpense.expense.event.ExpenseEventPublisher;
import com.splitexpense.expense.exception.InsufficientDebtException;
import com.splitexpense.expense.exception.NotSettlementPartyException;
import com.splitexpense.expense.exception.SameUserSettlementException;
import com.splitexpense.expense.mapper.SettlementMapper;
import com.splitexpense.expense.repository.IdempotencyRecordRepository;
import com.splitexpense.expense.repository.SettlementRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the settlement saga. {@link #settlementAppliesTheReverseDelta()} pins down
 * the one property this class exists to get right and previously did not: a settlement must
 * DECREASE what the payer owes the recipient, not add a second copy of the same debt on top
 * of it. See the delta-direction comment in {@link SettlementService#execute} for the
 * arithmetic; this test is what would have caught the bug that comment now documents, had it
 * existed first — a live run through the real stack is what actually caught it (`docker
 * compose up` end to end: Bob paid Alice back 500 and the balance went to 1000 owed instead
 * of 0), which is exactly the kind of directional bug unit tests with hand-picked fixtures
 * are prone to missing when the fixture happens to only exercise one direction.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID FROM_USER_ID = UUID.randomUUID();
    private static final UUID TO_USER_ID = UUID.randomUUID();
    private static final String AUTH_HEADER = "Bearer caller-token";
    private static final String KEY = "idem-key-1";

    @Mock private SettlementRepository settlementRepository;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock private GroupClient groupClient;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ExpenseEventPublisher expenseEventPublisher;

    private SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(
                settlementRepository,
                idempotencyRecordRepository,
                groupClient,
                auditService,
                new SettlementMapper(),
                objectMapper,
                expenseEventPublisher);

        lenient().when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static GroupView group() {
        List<GroupMemberView> members = List.of(
                new GroupMemberView(FROM_USER_ID, "MEMBER", Instant.now()),
                new GroupMemberView(TO_USER_ID, "MEMBER", Instant.now()));
        return new GroupView(
                GROUP_ID, "Flat", null, "INR", FROM_USER_ID, "ACTIVE", members,
                Instant.now(), Instant.now());
    }

    private static CreateSettlementRequest request() {
        return new CreateSettlementRequest(GROUP_ID, FROM_USER_ID, TO_USER_ID, new BigDecimal("500.00"), null);
    }

    /** A debt graph where {@code FROM_USER_ID} owes {@code TO_USER_ID} exactly {@code owed}. */
    private static GroupBalancesView balances(String owed) {
        return new GroupBalancesView(
                GROUP_ID, "INR",
                List.of(new PairBalanceView(FROM_USER_ID, TO_USER_ID, new BigDecimal(owed))),
                List.of());
    }

    @Test
    @DisplayName("a settlement applies a delta that DECREASES what the payer owes the recipient")
    void settlementAppliesTheReverseDelta() {
        when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group());
        when(groupClient.getBalances(GROUP_ID, AUTH_HEADER)).thenReturn(balances("500.00"));
        when(idempotencyRecordRepository.findById(KEY)).thenReturn(java.util.Optional.empty());
        when(idempotencyRecordRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.saveAndFlush(any(Settlement.class))).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(groupClient.apply(eq(GROUP_ID), any()))
                .thenReturn(new ApplyBalancesResult(GROUP_ID, "ref", true, null));

        service.recordSettlement(FROM_USER_ID, KEY, request(), AUTH_HEADER);

        ArgumentCaptor<ApplyBalancesPayload> captor = ArgumentCaptor.forClass(ApplyBalancesPayload.class);
        verify(groupClient).apply(eq(GROUP_ID), captor.capture());

        // A BalanceDeltaPayload(debtorId, creditorId, amount) always INCREASES what debtorId
        // owes creditorId. fromUserId just paid toUserId, so fromUserId's debt to toUserId
        // must go DOWN — which means the delta's debtor/creditor must be the REVERSE of the
        // settlement's own from/to fields. Asserting the swap directly, rather than asserting
        // some derived balance number, is what makes this test fail loudly and specifically
        // if the direction is ever flipped back.
        assertThat(captor.getValue().deltas()).hasSize(1);
        assertThat(captor.getValue().deltas().get(0).debtorId()).isEqualTo(TO_USER_ID);
        assertThat(captor.getValue().deltas().get(0).creditorId()).isEqualTo(FROM_USER_ID);
        assertThat(captor.getValue().reason()).isEqualTo("SETTLEMENT");
    }

    @Test
    @DisplayName("rejects settling with yourself, before any group-service call")
    void rejectsSameUserSettlement() {
        CreateSettlementRequest sameUser =
                new CreateSettlementRequest(GROUP_ID, FROM_USER_ID, FROM_USER_ID, new BigDecimal("10.00"), null);

        assertThatThrownBy(() -> service.recordSettlement(FROM_USER_ID, KEY, sameUser, AUTH_HEADER))
                .isInstanceOf(SameUserSettlementException.class);
    }

    @Test
    @DisplayName("rejects a caller who is neither party to the settlement")
    void rejectsNonParty() {
        UUID bystander = UUID.randomUUID();

        assertThatThrownBy(() -> service.recordSettlement(bystander, KEY, request(), AUTH_HEADER))
                .isInstanceOf(NotSettlementPartyException.class);
    }

    @Test
    @DisplayName("rejects settling up when the payer owes the recipient nothing at all")
    void rejectsSettlementWithNoDebt() {
        when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group());
        // No pair for FROM_USER_ID -> TO_USER_ID at all: settled, or never transacted.
        when(groupClient.getBalances(GROUP_ID, AUTH_HEADER))
                .thenReturn(new GroupBalancesView(GROUP_ID, "INR", List.of(), List.of()));

        assertThatThrownBy(() -> service.recordSettlement(FROM_USER_ID, KEY, request(), AUTH_HEADER))
                .isInstanceOf(InsufficientDebtException.class);
    }

    @Test
    @DisplayName("rejects settling for more than the payer currently owes")
    void rejectsSettlementExceedingTheDebt() {
        when(groupClient.getGroupForCaller(GROUP_ID, AUTH_HEADER)).thenReturn(group());
        // request() asks to settle 500.00; only 100.00 is actually owed.
        when(groupClient.getBalances(GROUP_ID, AUTH_HEADER)).thenReturn(balances("100.00"));

        assertThatThrownBy(() -> service.recordSettlement(FROM_USER_ID, KEY, request(), AUTH_HEADER))
                .isInstanceOf(InsufficientDebtException.class);
    }
}
