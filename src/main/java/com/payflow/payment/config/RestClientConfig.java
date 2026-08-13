package com.payflow.payment.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The two outbound HTTP clients this service holds: one to auth-service, for obtaining a
 * service token, and one to wallet-service, for moving money.
 *
 * <p>Two beans rather than one shared client because the two remotes have independent base
 * URLs, independent timeout budgets, and independent failure semantics — wallet-service calls
 * are guarded by the {@code walletService} circuit breaker, auth-service calls are not (a
 * service token is refreshed roughly once every fifteen minutes, far too rarely for a breaker
 * to usefully judge auth-service's health from).
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient authRestClient(RestClient.Builder builder, AuthProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(timeoutFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
    }

    @Bean
    public RestClient walletRestClient(RestClient.Builder builder, WalletProperties properties) {
        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(timeoutFactory(properties.connectTimeout(), properties.readTimeout()))
                .build();
    }

    private ClientHttpRequestFactory timeoutFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return factory;
    }
}
