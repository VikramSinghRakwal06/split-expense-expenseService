package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole application against a real PostgreSQL and checks the three things that
 * the rest of the service is built on top of.
 *
 * <p>This is deliberately more than a {@code contextLoads()}. Two of the assertions cover
 * arrangements that would otherwise fail silently:
 *
 * <ul>
 *   <li>The circuit breaker and retry are configured entirely in YAML, so a typo in a
 *       property name does not fail the build — it produces a breaker running on
 *       resilience4j's defaults (sliding window 100, 50% threshold, 60s open) while
 *       {@code application.yml} claims otherwise. Reading the bound values back from the
 *       registry is the only way to know the configuration took effect.</li>
 *   <li>resilience4j 2.3.0 is the {@code spring-boot3} artifact running on Spring Boot 4.
 *       Its aspects were expected to survive that; asserting the registries exist and are
 *       populated is what turns that expectation into a checked fact.</li>
 * </ul>
 *
 * <p>Runs against Testcontainers rather than an in-memory database because the schema is
 * PostgreSQL-specific and Flyway applying it cleanly is part of what is being verified.
 */
@Testcontainers
@SpringBootTest(properties = {
        // Boot disables every metrics exporter inside @SpringBootTest (see
        // DisableMetricsExportContextCustomizer), which also removes the PrometheusMeterRegistry
        // the scrape endpoint is conditional on. Without this the endpoint assertion below
        // would be testing the test harness rather than application.yml.
        "management.prometheus.metrics.export.enabled=true"
})
class PaymentServiceApplicationTests {

    @Container
    @SuppressWarnings("resource") // Lifecycle is managed by the Testcontainers extension.
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payflow_payment")
            .withUsername("payflow")
            .withPassword("payflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Pinned so the test does not depend on whichever profile a developer has active.
        registry.add("payflow.jwt.secret",
                () -> "integration-test-secret-key-of-more-than-32-bytes");
        registry.add("payflow.jwt.issuer", () -> "payflow-auth-service");
    }

    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private RetryRegistry retryRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebEndpointsSupplier webEndpoints;

    @Test
    @DisplayName("Flyway applies V1 and creates all three tables")
    void migrationCreatesTheSchema() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables)
                .contains("transactions", "idempotency_records", "audit_logs");
    }

    /**
     * The idempotency design rests entirely on this constraint existing. If a future
     * migration drops or renames it, every duplicate-detection path in the service
     * degrades from "the database refuses the second insert" to "both inserts succeed and
     * the payment happens twice" — with no other test necessarily failing.
     */
    @Test
    @DisplayName("transactions.idempotency_key carries the UNIQUE constraint the flow relies on")
    void idempotencyKeyIsUnique() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'transactions'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'idempotency_key'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("All five configured actuator endpoints are exposed")
    void actuatorEndpointsAreExposed() {
        List<String> exposed = webEndpoints.getEndpoints().stream()
                .map(endpoint -> endpoint.getEndpointId().toString())
                .toList();

        assertThat(exposed)
                .contains("health", "info", "metrics", "prometheus", "circuitbreakers");
    }

    @Test
    @DisplayName("The walletService breaker and retry bind the values declared in YAML")
    void resilienceConfigurationIsApplied() {
        CircuitBreakerConfig breaker =
                circuitBreakerRegistry.circuitBreaker("walletService").getCircuitBreakerConfig();

        assertThat(breaker.getSlidingWindowSize()).isEqualTo(10);
        assertThat(breaker.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(breaker.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(10).toMillis());

        assertThat(retryRegistry.retry("walletService").getRetryConfig().getMaxAttempts())
                .isEqualTo(3);
    }
}
