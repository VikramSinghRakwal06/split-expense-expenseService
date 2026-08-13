package com.payflow.payment.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for payment-service's own identity in auth-service, bound from {@code
 * payflow.service-account.*}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>wallet-service's internal credit/debit endpoints require {@code ROLE_ADMIN} — see its
 * {@code SecurityConfig} — and the platform has no dedicated service-identity mechanism, only
 * the ordinary {@code USER}/{@code ADMIN} roles auth-service issues to people. Pending a real
 * machine-identity role or mutual TLS, payment-service authenticates the same way a person
 * would: it holds the credentials of one pre-provisioned {@code ADMIN} account and logs in
 * through the normal {@code /api/v1/auth/login} flow, exactly like wallet-service's
 * {@code SecurityConfig} javadoc anticipates.
 *
 * <h2>Provisioning</h2>
 *
 * <p>This account must exist in auth-service before payment-service can move any money:
 * register it via {@code POST /api/v1/auth/register} with this email and password, then
 * promote it by hand — {@code UPDATE users SET role = 'ADMIN' WHERE email = '...'} — since
 * auth-service has no self-service or seeded way to create an admin account.
 *
 * @param email    login identifier of the pre-provisioned admin account
 * @param password its password, verified by auth-service on every token refresh
 */
@Validated
@ConfigurationProperties(prefix = "payflow.service-account")
public record ServiceAccountProperties(

        @NotBlank(message = "payflow.service-account.email must be set") String email,

        @NotBlank(message = "payflow.service-account.password must be set") String password) {
}
