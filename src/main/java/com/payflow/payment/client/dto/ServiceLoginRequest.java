package com.payflow.payment.client.dto;

/**
 * Outbound payload for {@code POST /api/v1/auth/login} against auth-service, carrying
 * payment-service's own service-account credentials.
 *
 * <p>Field names match auth-service's {@code LoginRequest} exactly; there is no shared module
 * between the services, so this is a deliberate structural duplicate of a contract, not a
 * dependency.
 */
public record ServiceLoginRequest(String email, String password) {
}
