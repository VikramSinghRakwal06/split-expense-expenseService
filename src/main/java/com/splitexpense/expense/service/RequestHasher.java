package com.splitexpense.expense.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprint of a canonical request string, shared by {@link ExpenseService} and
 * {@link SettlementService}.
 *
 * <p>Used to detect a client reusing an {@code Idempotency-Key} with a request body that does
 * not match the one it was first used with — see {@code IdempotencyKeyConflictException}. The
 * hash, not the raw body, is what is stored: this table must not accumulate the full detail of
 * every expense and settlement ever attempted.
 */
final class RequestHasher {

    private RequestHasher() {
    }

    static String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK's standard algorithm list; unreachable in practice.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
