package com.splitexpense.expense.entity;

/**
 * How an expense's amount is divided between its participants.
 *
 * <p>Persisted as its {@code name()} string, mirrored by {@code ck_expenses_split_type}.
 * Interpreted by {@code com.splitexpense.expense.domain.SplitCalculator}, which is the only
 * place that reads this value to decide anything.
 */
public enum SplitType {

    /**
     * Divided as evenly as {@code NUMERIC(19,4)} allows. Participants carry no value of
     * their own; only the list of who took part is needed.
     */
    EQUAL,

    /**
     * Each participant's share is stated exactly, in money. The shares must sum to the
     * expense's total precisely — this is the one split type that does not tolerate rounding,
     * because the caller already did the arithmetic.
     */
    EXACT,

    /**
     * Each participant's share is stated as a percentage of the total. The percentages must
     * sum to 100.
     */
    PERCENTAGE,

    /**
     * Each participant's share is proportional to an integer weight — two housemates paying
     * double what a third pays, expressed as shares of 2, 2 and 1.
     */
    SHARES
}
