package com.payflow.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where wallet-service lives, bound from {@code payflow.wallet.*}.
 *
 * <p>{@code connectTimeout} and {@code readTimeout} are deliberately short and asymmetric —
 * see {@code application.yml} for the reasoning, which is exactly why a read timeout must be
 * treated as ambiguous rather than as a clean failure in {@link com.payflow.payment.client.WalletClient}.
 *
 * @param baseUrl        origin of wallet-service, no trailing slash
 * @param connectTimeout socket-connect budget
 * @param readTimeout    budget for a debit or credit call to complete
 */
@Validated
@ConfigurationProperties(prefix = "payflow.wallet")
public record WalletProperties(

        @NotBlank(message = "payflow.wallet.base-url must be set") String baseUrl,

        @NotNull(message = "payflow.wallet.connect-timeout must be set") Duration connectTimeout,

        @NotNull(message = "payflow.wallet.read-timeout must be set") Duration readTimeout) {
}
