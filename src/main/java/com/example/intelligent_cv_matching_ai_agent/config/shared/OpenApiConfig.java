package com.example.intelligent_cv_matching_ai_agent.config.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI api() {
        return new OpenAPI()
            .info(
                new Info().title("EduNexus Career API").version("v1").description(
                    ("Career profiles, reviewed CVs, job matching, learning, consultation " + "and subscriptions.")
                )
            )
            .components(
                new Components().addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("Opaque accessToken returned by login; expires after 15 minutes.")
                ).addSecuritySchemes(
                    "guestSession",
                    new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                        .name("X-Guest-Session")
                )
            );
    }

    @Bean
    OpenApiCustomizer operationSecurity() {
        return api -> api.getPaths().forEach(
            (
                path,
                item) -> item.readOperationsMap().forEach(
                    (
                        method,
                        operation) -> {
                        boolean publicAuth = List.of(
                            "/api/v1/auth/register",
                            "/api/v1/auth/login",
                            "/api/v1/auth/google",
                            "/api/v1/auth/verify-email",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/resend-code"
                        ).contains(path);
                        boolean publicGet = method.name().equals("GET") && (path.equals("/api/v1/jobs")
                            || path.equals("/api/v1/jobs/{id}") || path.equals("/api/v1/skills")
                            || path.equals("/api/v1/resumes/templates") || path.equals("/api/v1/subscriptions/tiers"));
                        if (publicAuth || publicGet || path.equals("/api/v1/guest/sessions")
                            || path.equals("/api/v1/payments/webhook"))
                            operation.setSecurity(List.of());
                        else
                            operation.setSecurity(
                                List.of(
                                    new SecurityRequirement()
                                        .addList(path.startsWith("/api/v1/guest/") ? "guestSession" : "bearerAuth")
                                )
                            );
                    }
                )
        );
    }
}
