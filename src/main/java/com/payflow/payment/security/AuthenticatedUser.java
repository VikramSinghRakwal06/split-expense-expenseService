package com.payflow.payment.security;

import java.util.UUID;

/**
 * The caller, as stated by a verified JWT and nothing else.
 *
 * <p>This is the {@code Authentication} principal for every request, reachable in a
 * controller with {@code @AuthenticationPrincipal AuthenticatedUser caller}.
 *
 * <p>Note what it is <em>not</em>: it is not a {@code UserDetails}, and there is no {@code
 * UserDetailsService} behind it. auth-service owns the user table, in a different database
 * this service cannot read, so there is nobody to look up. Everything known about the caller
 * is what the token asserts.
 *
 * <p>{@code id} is the account identifier this service resolves the caller's wallet from (via
 * wallet-service). It comes from the signed {@code uid} claim and can therefore never be
 * influenced by a request parameter.
 *
 * @param id    the account's identifier, from the {@code uid} claim
 * @param email login identifier, from the {@code sub} claim; carried for logging only
 * @param role  authorisation role, from the {@code role} claim, without the {@code ROLE_}
 *              prefix Spring Security adds
 */
public record AuthenticatedUser(UUID id, String email, String role) {
}
