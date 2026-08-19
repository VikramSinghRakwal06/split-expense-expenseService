package com.splitexpense.expense.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where group-service lives, bound from {@code splitexpense.group.*}.
 *
 * <p>{@code connectTimeout} and {@code readTimeout} are deliberately short and asymmetric —
 * see {@code application.yml} for the reasoning. Unlike the wallet-service client this
 * replaces, a read timeout on the apply call is not dangerous: group-service's
 * {@code :apply} endpoint is idempotent by this service's own reference id, so an ambiguous
 * outcome costs a retry, not a compensation.
 *
 * @param baseUrl        origin of group-service, no trailing slash
 * @param connectTimeout socket-connect budget
 * @param readTimeout    budget for a read or an apply call to complete
 */
@Validated
@ConfigurationProperties(prefix = "splitexpense.group")
public record GroupProperties(

        @NotBlank(message = "splitexpense.group.base-url must be set") String baseUrl,

        @NotNull(message = "splitexpense.group.connect-timeout must be set") Duration connectTimeout,

        @NotNull(message = "splitexpense.group.read-timeout must be set") Duration readTimeout) {
}
