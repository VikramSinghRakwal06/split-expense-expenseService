package com.splitexpense.expense.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors group-service's {@code GroupMemberResponse} field for field.
 *
 * <p>{@code role} is read as a plain string rather than an enum: this service has no business
 * decision that depends on {@code OWNER} vs {@code MEMBER} — it only needs to know
 * <em>who</em> is in the group, for validating expense participants — so there is nothing to
 * gain from coupling to group-service's role vocabulary and something to lose if that
 * vocabulary grows.
 */
public record GroupMemberView(UUID userId, String role, Instant joinedAt) {
}
