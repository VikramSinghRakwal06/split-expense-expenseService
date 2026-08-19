package com.splitexpense.expense.exception;

/**
 * The caller recording a settlement is neither the payer nor the recipient named in it.
 *
 * <p>A settlement may be recorded by either party — the one who paid, confirming it went out,
 * or the one who received it, confirming it came in — but not by an uninvolved third member,
 * even one who belongs to the same group. Mapped to 403: the caller has already proven
 * membership of the group by the time this check runs, so its existence is not a secret from
 * them; they simply lack standing over this specific debt.
 */
public class NotSettlementPartyException extends RuntimeException {

    public NotSettlementPartyException(String message) {
        super(message);
    }
}
