package com.splitexpense.expense.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Mirrors group-service's {@code MemberNetPositionResponse}. Declared only so {@link
 *  GroupBalancesView} deserialises without relying on unknown-property leniency — no caller
 *  in this service reads it. */
public record MemberNetPositionView(UUID userId, BigDecimal net) {
}
