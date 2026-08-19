package com.splitexpense.expense.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mirrors group-service's {@code GroupResponse} field for field.
 *
 * <p>Matching the full shape, rather than declaring only the fields this service reads,
 * avoids relying on unknown-property leniency in the Jackson 3 ({@code tools.jackson})
 * ObjectMapper — this module ships no separate annotations artifact to opt out of strict
 * binding with, unlike classic Jackson 2.
 */
public record GroupView(
        UUID id,
        String name,
        String description,
        String currency,
        UUID createdBy,
        String status,
        List<GroupMemberView> members,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * @return the user ids currently in this group
     */
    public java.util.Set<UUID> memberIds() {
        return members.stream().map(GroupMemberView::userId).collect(java.util.stream.Collectors.toSet());
    }
}
