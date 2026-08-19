package com.splitexpense.expense.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for expense-service's own identity in auth-service, bound from {@code
 * splitexpense.service-account.*}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>group-service's internal {@code :apply} endpoint requires {@code ROLE_ADMIN} — see its
 * {@code SecurityConfig} — and the platform has no dedicated service-identity mechanism, only
 * the ordinary {@code USER}/{@code ADMIN} roles auth-service issues to people. Pending a real
 * machine-identity role or mutual TLS, expense-service authenticates the same way a person
 * would: it holds the credentials of one pre-provisioned {@code ADMIN} account and logs in
 * through the normal {@code /api/v1/auth/login} flow, exactly like group-service's
 * {@code SecurityConfig} javadoc anticipates.
 *
 * <h2>Provisioning</h2>
 *
 * <p>This account must exist in auth-service before expense-service can apply any balance
 * changes: register it via {@code POST /api/v1/auth/register} with this email and password,
 * then promote it by hand — {@code UPDATE users SET role = 'ADMIN' WHERE email = '...'} —
 * since auth-service has no self-service or seeded way to create an admin account.
 *
 * @param email    login identifier of the pre-provisioned admin account
 * @param password its password, verified by auth-service on every token refresh
 */
@Validated
@ConfigurationProperties(prefix = "splitexpense.service-account")
public record ServiceAccountProperties(

        @NotBlank(message = "splitexpense.service-account.email must be set") String email,

        @NotBlank(message = "splitexpense.service-account.password must be set") String password) {
}
