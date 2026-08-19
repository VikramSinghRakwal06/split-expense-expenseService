package com.splitexpense.expense.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the service, including the bearer scheme so the Swagger UI can call
 * the protected endpoints with a token obtained from auth-service.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI expenseServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SplitExpense Expense Service")
                        .version("v1")
                        .description("""
                                Expenses, splits and settlements applied to group-service's \
                                debt graph. Every expense and settlement is keyed by a \
                                client-supplied Idempotency-Key, so a retried request is \
                                always safe. Authenticates with access tokens issued by \
                                auth-service; this service verifies them and never mints \
                                one.""")
                        .contact(new Contact().name("SplitExpense Platform"))
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "Access token returned by auth-service /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
