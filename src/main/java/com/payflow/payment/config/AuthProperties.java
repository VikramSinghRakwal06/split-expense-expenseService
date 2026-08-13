package com.payflow.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where auth-service lives, bound from {@code payflow.auth.*}.
 *
 * <p>Used for exactly one call: {@link com.payflow.payment.client.AdminTokenProvider} logging
 * in as this service's own service account to obtain a token for calling wallet-service's
 * internal endpoints. This service never validates a token against auth-service directly —
 * inbound tokens are verified locally, the same way wallet-service does it.
 *
 * @param baseUrl        origin of auth-service, no trailing slash
 * @param connectTimeout socket-connect budget
 * @param readTimeout    response budget; the login call is on the critical path of every
 *                       transfer once the cached token expires, so this should stay small
 */
@Validated
@ConfigurationProperties(prefix = "payflow.auth")
public record AuthProperties(

        @NotBlank(message = "payflow.auth.base-url must be set") String baseUrl,

        @NotNull(message = "payflow.auth.connect-timeout must be set") Duration connectTimeout,

        @NotNull(message = "payflow.auth.read-timeout must be set") Duration readTimeout) {
}
