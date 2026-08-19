package com.splitexpense.expense.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitexpense.expense.domain.SplitCalculator.ParticipantInput;
import com.splitexpense.expense.entity.SplitType;
import com.splitexpense.expense.exception.InvalidSplitException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for the pure split arithmetic.
 *
 * <p>The property that matters more than any individual example is
 * {@link #sharesAlwaysSumToTheTotalExactly}: for every split type, over many random totals and
 * participant counts, the computed shares must sum to the total to the last {@code 0.0001}.
 * That is the invariant the whole debt graph on the group-service side depends on — if it ever
 * fails, an expense would leave a fraction of itself unaccounted for in every group it touches.
 */
class SplitCalculatorTest {

    private static UUID user(int n) {
        // Fixed, low-entropy ids so residual-distribution tests can predict exactly who
        // absorbs the leftover fraction without depending on random UUID ordering.
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(n));
    }

    private static ParticipantInput equal(int n) {
        return new ParticipantInput(user(n), null);
    }

    private static ParticipantInput valued(int n, String value) {
        return new ParticipantInput(user(n), new BigDecimal(value));
    }

    @Nested
    @DisplayName("EQUAL")
    class Equal {

        @Test
        @DisplayName("splits evenly when it divides exactly")
        void dividesExactly() {
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.EQUAL, new BigDecimal("300.0000"),
                    List.of(equal(1), equal(2), equal(3)));

            assertThat(shares.values())
                    .allSatisfy(v -> assertThat(v).isEqualByComparingTo("100.0000"));
        }

        @Test
        @DisplayName("hands the leftover fraction to the lowest user ids")
        void distributesResidualToLowestIds() {
            // 100.00 / 3 = 33.3333 each, leaving 0.0001 over.
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.EQUAL, new BigDecimal("100.0000"),
                    List.of(equal(3), equal(1), equal(2)));

            assertThat(shares.get(user(1))).isEqualByComparingTo("33.3334");
            assertThat(shares.get(user(2))).isEqualByComparingTo("33.3333");
            assertThat(shares.get(user(3))).isEqualByComparingTo("33.3333");
        }

        @Test
        @DisplayName("a single participant takes the whole amount")
        void singleParticipant() {
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.EQUAL, new BigDecimal("450.0000"), List.of(equal(1)));

            assertThat(shares).hasSize(1);
            assertThat(shares.get(user(1))).isEqualByComparingTo("450.0000");
        }
    }

    @Nested
    @DisplayName("EXACT")
    class Exact {

        @Test
        @DisplayName("takes stated shares verbatim when they sum correctly")
        void acceptsMatchingShares() {
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.EXACT, new BigDecimal("100.0000"),
                    List.of(valued(1, "60.00"), valued(2, "40.00")));

            assertThat(shares.get(user(1))).isEqualByComparingTo("60.0000");
            assertThat(shares.get(user(2))).isEqualByComparingTo("40.0000");
        }

        @Test
        @DisplayName("rejects shares that do not sum to the total, without adjusting them")
        void rejectsMismatchedSum() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.EXACT, new BigDecimal("100.0000"),
                    List.of(valued(1, "60.00"), valued(2, "30.00"))))
                    .isInstanceOf(InvalidSplitException.class)
                    .hasMessageContaining("sum to the expense amount exactly");
        }

        @Test
        @DisplayName("rejects a negative exact amount")
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.EXACT, new BigDecimal("100.0000"),
                    List.of(valued(1, "-10.00"), valued(2, "110.00"))))
                    .isInstanceOf(InvalidSplitException.class);
        }

        @Test
        @DisplayName("accepts a zero exact share for a participant named for nothing")
        void allowsZeroShare() {
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.EXACT, new BigDecimal("100.0000"),
                    List.of(valued(1, "100.00"), valued(2, "0.00")));

            assertThat(shares.get(user(2))).isEqualByComparingTo("0.0000");
        }
    }

    @Nested
    @DisplayName("PERCENTAGE")
    class Percentage {

        @Test
        @DisplayName("splits by percentage when they sum to 100")
        void splitsByPercentage() {
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.PERCENTAGE, new BigDecimal("200.0000"),
                    List.of(valued(1, "75"), valued(2, "25")));

            assertThat(shares.get(user(1))).isEqualByComparingTo("150.0000");
            assertThat(shares.get(user(2))).isEqualByComparingTo("50.0000");
        }

        @Test
        @DisplayName("rejects percentages that do not sum to 100")
        void rejectsMismatchedPercentages() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.PERCENTAGE, new BigDecimal("100.0000"),
                    List.of(valued(1, "50"), valued(2, "40"))))
                    .isInstanceOf(InvalidSplitException.class)
                    .hasMessageContaining("sum to exactly 100");
        }

        @Test
        @DisplayName("rejects a non-positive percentage")
        void rejectsNonPositivePercentage() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.PERCENTAGE, new BigDecimal("100.0000"),
                    List.of(valued(1, "0"), valued(2, "100"))))
                    .isInstanceOf(InvalidSplitException.class);
        }

        @Test
        @DisplayName("distributes the residual when percentages don't divide evenly")
        void distributesResidual() {
            // 100 * 1/3 = 33.3333, three times, leaving 0.0001.
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.PERCENTAGE, new BigDecimal("100.0000"),
                    List.of(valued(1, "33.33"), valued(2, "33.33"), valued(3, "33.34")));

            BigDecimal total = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(total).isEqualByComparingTo("100.0000");
        }
    }

    @Nested
    @DisplayName("SHARES")
    class Shares {

        @Test
        @DisplayName("splits proportionally to integer weights")
        void splitsByWeight() {
            // Two housemates at weight 2, one at weight 1: 900 split 400/400/... wait, 4 parts.
            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(
                    SplitType.SHARES, new BigDecimal("400.0000"),
                    List.of(valued(1, "2"), valued(2, "2"), valued(3, "1")));

            assertThat(shares.get(user(1))).isEqualByComparingTo("160.0000");
            assertThat(shares.get(user(2))).isEqualByComparingTo("160.0000");
            assertThat(shares.get(user(3))).isEqualByComparingTo("80.0000");
        }

        @Test
        @DisplayName("rejects a fractional or non-positive weight")
        void rejectsInvalidWeight() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.SHARES, new BigDecimal("100.0000"),
                    List.of(valued(1, "1.5"), valued(2, "1"))))
                    .isInstanceOf(InvalidSplitException.class);

            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.SHARES, new BigDecimal("100.0000"),
                    List.of(valued(1, "0"), valued(2, "1"))))
                    .isInstanceOf(InvalidSplitException.class);
        }
    }

    @Nested
    @DisplayName("input validation shared by every split type")
    class SharedValidation {

        @Test
        @DisplayName("rejects an empty participant list")
        void rejectsEmpty() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.EQUAL, new BigDecimal("100.0000"), List.of()))
                    .isInstanceOf(InvalidSplitException.class);
        }

        @Test
        @DisplayName("rejects a participant listed twice")
        void rejectsDuplicateParticipant() {
            assertThatThrownBy(() -> SplitCalculator.computeShares(
                    SplitType.EQUAL, new BigDecimal("100.0000"),
                    List.of(equal(1), equal(1))))
                    .isInstanceOf(InvalidSplitException.class)
                    .hasMessageContaining("twice");
        }
    }

    /**
     * The property test. Runs every split type against many random totals and participant
     * counts and checks the one thing that must never fail: the shares sum to the total.
     */
    @ParameterizedTest
    @EnumSource(SplitType.class)
    @DisplayName("shares always sum to the total exactly, for any total and participant count")
    void sharesAlwaysSumToTheTotalExactly(SplitType splitType) {
        var random = new Random(42);

        for (int trial = 0; trial < 500; trial++) {
            int participantCount = 1 + random.nextInt(12);
            BigDecimal total = randomAmount(random);

            List<ParticipantInput> participants = inputsFor(splitType, participantCount, total, random);

            Map<UUID, BigDecimal> shares = SplitCalculator.computeShares(splitType, total, participants);

            BigDecimal sum = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum)
                    .as("trial %d: %s split of %s across %d participants",
                            trial, splitType, total, participantCount)
                    .isEqualByComparingTo(total);

            assertThat(shares.values())
                    .as("no share may be negative")
                    .allSatisfy(share -> assertThat(share).isGreaterThanOrEqualTo(BigDecimal.ZERO));
        }
    }

    @RepeatedTest(20)
    @DisplayName("EQUAL is deterministic regardless of participant list order")
    void equalSplitIsOrderIndependent() {
        var random = new Random();
        int count = 2 + random.nextInt(9);
        BigDecimal total = randomAmount(random);

        List<ParticipantInput> ordered = IntStream.range(0, count)
                .mapToObj(SplitCalculatorTest::equal)
                .collect(Collectors.toList());
        List<ParticipantInput> shuffled = new ArrayList<>(ordered);
        Collections.shuffle(shuffled, random);

        Map<UUID, BigDecimal> a = SplitCalculator.computeShares(SplitType.EQUAL, total, ordered);
        Map<UUID, BigDecimal> b = SplitCalculator.computeShares(SplitType.EQUAL, total, shuffled);

        assertThat(a).isEqualTo(b);
    }

    private static BigDecimal randomAmount(Random random) {
        // Something with cents that usually won't divide evenly by small participant counts.
        long cents = 1 + random.nextInt(10_000_00);
        return BigDecimal.valueOf(cents, 2).setScale(4);
    }

    private static List<ParticipantInput> inputsFor(
            SplitType splitType, int count, BigDecimal total, Random random) {

        return switch (splitType) {
            case EQUAL -> IntStream.range(0, count)
                    .mapToObj(SplitCalculatorTest::equal)
                    .toList();

            case SHARES -> IntStream.range(0, count)
                    .mapToObj(i -> new ParticipantInput(user(i), BigDecimal.valueOf(1 + random.nextInt(5))))
                    .toList();

            case PERCENTAGE -> percentageInputs(count, random);

            case EXACT -> exactInputs(count, total, random);
        };
    }

    /** Random positive percentages that sum to exactly 100. */
    private static List<ParticipantInput> percentageInputs(int count, Random random) {
        int[] whole = new int[count];
        int remaining = 100 - count; // reserve 1 for each, distribute the rest
        for (int i = 0; i < count - 1; i++) {
            int share = remaining > 0 ? random.nextInt(remaining + 1) : 0;
            whole[i] = 1 + share;
            remaining -= share;
        }
        whole[count - 1] = 1 + remaining;

        List<ParticipantInput> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add(new ParticipantInput(user(i), BigDecimal.valueOf(whole[i])));
        }
        return inputs;
    }

    /** Splits {@code total} into random exact shares that sum to it precisely. */
    private static List<ParticipantInput> exactInputs(int count, BigDecimal total, Random random) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal remaining = total;
        for (int i = 0; i < count - 1; i++) {
            BigDecimal max = remaining;
            BigDecimal share = max.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : max.multiply(BigDecimal.valueOf(random.nextDouble()))
                            .setScale(4, RoundingMode.DOWN);
            shares.add(share);
            remaining = remaining.subtract(share);
        }
        shares.add(remaining);

        List<ParticipantInput> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            inputs.add(new ParticipantInput(user(i), shares.get(i)));
        }
        return inputs;
    }
}
