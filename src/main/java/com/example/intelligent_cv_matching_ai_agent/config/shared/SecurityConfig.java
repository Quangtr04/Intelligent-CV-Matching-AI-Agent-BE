package com.example.intelligent_cv_matching_ai_agent.config.shared;

import com.example.intelligent_cv_matching_ai_agent.security.shared.BearerFilter;
import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain security(
        HttpSecurity http,
        BearerFilter bearer)
        throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(cors -> {
        }).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(
                auth -> auth
                    // Preserve the original HTTP error when the container dispatches to /error.
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-code",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/google",
                        "/api/v1/guest/**",
                        "/api/v1/payments/webhook",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                    ).permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/skills",
                        "/api/v1/jobs",
                        "/api/v1/jobs/*",
                        "/api/v1/subscriptions/tiers",
                        "/api/v1/resumes/templates"
                    ).permitAll().requestMatchers("/api/v1/admin/**", "/api/admin/**").hasRole("ADMIN").anyRequest()
                    .authenticated()
            ).exceptionHandling(
                ex -> ex.authenticationEntryPoint(
                    (
                        req,
                        res,
                        err) -> {
                        res.setStatus(401);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"status\":401,\"message\":\"Authentication required\"}");
                    }
                ).accessDeniedHandler(
                    (
                        req,
                        res,
                        err) -> {
                        res.setStatus(403);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"status\":403,\"message\":\"Access denied\"}");
                    }
                )
            ).addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class).build();
    }

    // The filter belongs to Spring Security only; registering it twice bypasses its
    // context lifecycle.
    @Bean
    FilterRegistrationBean<BearerFilter> bearerRegistration(
        BearerFilter bearer) {
        FilterRegistrationBean<BearerFilter> registration = new FilterRegistrationBean<>(bearer);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource corsSource(
        @Value("${app.security.allowed-origins:http://localhost:3000,http://localhost:5173}")
        String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Guest-Session"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
