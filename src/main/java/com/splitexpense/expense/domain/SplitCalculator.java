package com.splitexpense.expense.domain;

import com.splitexpense.expense.entity.SplitType;
import com.splitexpense.expense.exception.InvalidSplitException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns one expense's amount and its participants' inputs into exact per-person shares.
 *
 * <p>A pure function over its arguments — no repository, no service, nothing but
 * {@link BigDecimal} arithmetic — which is what makes it exhaustively unit-testable and safe
 * to call from {@code ExpenseService} before anything is persisted.
 *
 * <h2>The problem every split type shares: rounding</h2>
 *
 * <p>Splitting 100.00 three ways at the ledger's own scale gives 33.3333 each, summing to
 * 99.9999. One ten-thousandth is unaccounted for. Left unhandled, every uneven split leaks a
 * residual, and a group's balances would never quite sum to zero — the one invariant
 * {@code GroupBalanceMapper} on the group-service side relies on. This class exists
 * specifically so that invariant is never at the mercy of a caller's arithmetic:
 * {@link #computeShares} always returns shares that sum to the total <em>exactly</em>.
 *
 * <p>The method, applied identically by every split type below:
 * <ol>
 *   <li>Compute each participant's raw share, floored to {@link #MONEY_SCALE} with
 *       {@link RoundingMode#DOWN} — so no share is ever rounded up past what was actually
 *       allocated to them.</li>
 *   <li>The residual — {@code total - Σshares} — is by construction a non-negative whole
 *       number of {@code 0.0001} units, strictly fewer than the participant count.</li>
 *   <li>Hand out one {@code 0.0001} unit at a time to participants ordered by user id
 *       ascending, until the residual reaches zero.</li>
 * </ol>
 *
 * <p>Ordering by user id rather than by input order makes the outcome depend only on
 * <em>who</em> is participating, not on what order the client happened to list them in — so
 * the same expense submitted twice, even with its participant list shuffled, produces
 * byte-identical splits. That determinism is what the idempotent replay in
 * {@code ExpenseService} depends on: two attempts of the same logical request must compute
 * the same shares, or a retry could plausibly return an answer that disagrees with the first.
 * It does mean the same low-numbered participant absorbs the extra fraction on every expense
 * that rounds their way; at {@code 0.0001} per expense that is immaterial.
 */
public final class SplitCalculator {

    /** Matches {@code NUMERIC(19,4)}: 4 decimal places, and no finer. */
    private static final int MONEY_SCALE = 4;

    /** The smallest unit this ledger can represent: {@code 0.0001}. */
    private static final BigDecimal SMALLEST_UNIT = BigDecimal.ONE.movePointLeft(MONEY_SCALE);

    private SplitCalculator() {
    }

    /**
     * One participant's input to a split: who they are, and — depending on
     * {@link SplitType} — the value that decides their share.
     *
     * @param userId the participant
     * @param value  ignored for {@link SplitType#EQUAL}; the exact share for
     *               {@link SplitType#EXACT}; the percentage (0–100) for
     *               {@link SplitType#PERCENTAGE}; the integer weight for
     *               {@link SplitType#SHARES}
     */
    public record ParticipantInput(UUID userId, BigDecimal value) {
    }

    /**
     * Computes exact shares for every participant.
     *
     * @param splitType    how to divide the total
     * @param totalAmount  the expense's full amount; must already be validated positive and
     *                     at {@link #MONEY_SCALE} or coarser by the caller
     * @param participants who took part, in any order, with the input each split type needs;
     *                     duplicate user ids are rejected
     * @return each participant's share, keyed by user id, summing to {@code totalAmount}
     *         exactly
     * @throws InvalidSplitException if the participant list is empty, contains a duplicate,
     *                               or the split type's own inputs are invalid (see each
     *                               private method for the specific rules)
     */
    public static Map<UUID, BigDecimal> computeShares(
            SplitType splitType, BigDecimal totalAmount, List<ParticipantInput> participants) {

        requireDistinctAndNonEmpty(participants);

        Map<UUID, BigDecimal> shares = switch (splitType) {
            case EQUAL -> equalShares(totalAmount, participants);
            case EXACT -> exactShares(totalAmount, participants);
            case PERCENTAGE -> percentageShares(totalAmount, participants);
            case SHARES -> weightedShares(totalAmount, participants);
        };

        assert sumOf(shares.values()).compareTo(totalAmount) == 0
                : "computeShares must always return shares summing to the total exactly";

        return shares;
    }

    /**
     * Evenly, then distributes the leftover fraction to the lowest-numbered participants.
     */
    private static Map<UUID, BigDecimal> equalShares(
            BigDecimal totalAmount, List<ParticipantInput> participants) {

        BigDecimal each = totalAmount
                .divide(BigDecimal.valueOf(participants.size()), MONEY_SCALE, RoundingMode.DOWN);

        Map<UUID, BigDecimal> shares = new java.util.LinkedHashMap<>();
        for (ParticipantInput participant : participants) {
            shares.put(participant.userId(), each);
        }

        return distributeResidual(shares, totalAmount);
    }

    /**
     * Takes each participant's stated share verbatim. The one split type that tolerates no
     * rounding at all: the caller already did the arithmetic, and silently adjusting it would
     * mean the recorded splits do not match what the client showed the user before they
     * confirmed.
     *
     * @throws InvalidSplitException if any share is missing or negative, or the shares do not
     *                               sum to {@code totalAmount} exactly
     */
    private static Map<UUID, BigDecimal> exactShares(
            BigDecimal totalAmount, List<ParticipantInput> participants) {

        Map<UUID, BigDecimal> shares = new java.util.LinkedHashMap<>();
        for (ParticipantInput participant : participants) {
            BigDecimal value = participant.value();
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                    || value.scale() > MONEY_SCALE) {
                throw new InvalidSplitException(
                        "Each participant needs a non-negative exact amount with at most "
                                + MONEY_SCALE + " decimal places");
            }
            shares.put(participant.userId(), value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));
        }

        if (sumOf(shares.values()).compareTo(totalAmount) != 0) {
            throw new InvalidSplitException(
                    "Exact shares must sum to the expense amount exactly");
        }

        return shares;
    }

    /**
     * Each participant's percentage of the total, floored, then the leftover fraction
     * distributed to the lowest-numbered participants.
     *
     * @throws InvalidSplitException if any percentage is missing or non-positive, or the
     *                               percentages do not sum to exactly 100
     */
    private static Map<UUID, BigDecimal> percentageShares(
            BigDecimal totalAmount, List<ParticipantInput> participants) {

        BigDecimal percentageTotal = BigDecimal.ZERO;
        for (ParticipantInput participant : participants) {
            BigDecimal value = participant.value();
            if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidSplitException(
                        "Each participant needs a positive percentage");
            }
            percentageTotal = percentageTotal.add(value);
        }
        if (percentageTotal.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new InvalidSplitException("Percentages must sum to exactly 100");
        }

        Map<UUID, BigDecimal> shares = new java.util.LinkedHashMap<>();
        for (ParticipantInput participant : participants) {
            BigDecimal raw = totalAmount
                    .multiply(participant.value())
                    .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.DOWN);
            shares.put(participant.userId(), raw);
        }

        return distributeResidual(shares, totalAmount);
    }

    /**
     * Proportional to each participant's integer weight, floored, then the leftover fraction
     * distributed to the lowest-numbered participants.
     *
     * @throws InvalidSplitException if any weight is missing or not a positive integer
     */
    private static Map<UUID, BigDecimal> weightedShares(
            BigDecimal totalAmount, List<ParticipantInput> participants) {

        long totalWeight = 0;
        for (ParticipantInput participant : participants) {
            BigDecimal value = participant.value();
            if (value == null || value.scale() > 0 || value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new InvalidSplitException(
                        "Each participant needs a positive whole-number share weight");
            }
            totalWeight += value.longValueExact();
        }

        Map<UUID, BigDecimal> shares = new java.util.LinkedHashMap<>();
        for (ParticipantInput participant : participants) {
            BigDecimal raw = totalAmount
                    .multiply(participant.value())
                    .divide(BigDecimal.valueOf(totalWeight), MONEY_SCALE, RoundingMode.DOWN);
            shares.put(participant.userId(), raw);
        }

        return distributeResidual(shares, totalAmount);
    }

    /**
     * Hands out the leftover {@code 0.0001} units — always fewer than the participant count —
     * to the lowest user ids, in place, and returns the now-exact map.
     *
     * <p>Ordered by plain {@link UUID#compareTo}, not the PostgreSQL-matching order
     * group-service's {@code UserPair} uses: nothing here is compared against a database
     * {@code CHECK} constraint, so only <em>a</em> deterministic order is required, not that
     * specific one.
     */
    private static Map<UUID, BigDecimal> distributeResidual(
            Map<UUID, BigDecimal> shares, BigDecimal totalAmount) {

        BigDecimal residual = totalAmount.subtract(sumOf(shares.values()));
        if (residual.compareTo(BigDecimal.ZERO) == 0) {
            return shares;
        }

        List<UUID> byUserId = new ArrayList<>(shares.keySet());
        byUserId.sort(Comparator.naturalOrder());

        int units = residual.divide(SMALLEST_UNIT, 0, RoundingMode.UNNECESSARY).intValueExact();
        for (int i = 0; i < units; i++) {
            UUID recipient = byUserId.get(i);
            shares.put(recipient, shares.get(recipient).add(SMALLEST_UNIT));
        }

        return shares;
    }

    private static void requireDistinctAndNonEmpty(List<ParticipantInput> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new InvalidSplitException("An expense needs at least one participant");
        }
        long distinctCount = participants.stream().map(ParticipantInput::userId).distinct().count();
        if (distinctCount != participants.size()) {
            throw new InvalidSplitException("A participant cannot appear twice in one expense");
        }
    }

    private static BigDecimal sumOf(Iterable<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE);
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return sum;
    }
}
