package com.payflow.payment.config;

import com.payflow.payment.security.JwtAuthenticationEntryPoint;
import com.payflow.payment.security.JwtAuthenticationFilter;
import com.payflow.payment.security.RestAccessDeniedHandler;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Web security for payment-service: stateless bearer-token authentication.
 *
 * <p>Unlike wallet-service, this service exposes no endpoint that names an arbitrary wallet or
 * account — {@code POST /api/v1/payments/transfer} and {@code GET /api/v1/payments/me} both
 * resolve the caller's own wallet from the verified JWT, via wallet-service's {@code /me}. So
 * there is no {@code ROLE_ADMIN}-only rule here: every business endpoint needs only an
 * authenticated caller, the same as wallet-service's {@code /me} family.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Health probes and the API contract, needed by orchestrators and client tooling. */
    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
        "/actuator/health",
        "/actuator/health/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/v3/api-docs",
        "/v3/api-docs/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No ambient credential (cookie/session) exists for a cross-site request to
                // ride on — the only credential is a bearer token a script must attach itself.
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Stops Boot from also registering {@link JwtAuthenticationFilter} directly with the
     * servlet container, outside the security chain's intended position.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterContainerRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
