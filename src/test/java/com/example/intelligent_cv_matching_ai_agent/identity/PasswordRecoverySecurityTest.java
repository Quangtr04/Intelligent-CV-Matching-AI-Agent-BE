package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.config.shared.SecurityConfig;
import com.example.intelligent_cv_matching_ai_agent.controller.shared.identity.AuthController;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ForgotPasswordRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ResetPasswordRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiExceptionHandler;
import com.example.intelligent_cv_matching_ai_agent.security.shared.BearerFilter;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthFlowService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordRecoverySecurityTest {

    private static final String FORGOT_PASSWORD = "/api/v1/auth/forgot-password";

    private static final String RESET_PASSWORD = "/api/v1/auth/reset-password";

    private static final String EMAIL = "candidate@example.invalid";

    private static final String PASSWORD = "new-fixture-password";

    private static final String TOKEN = "p_" + "a".repeat(43);

    private AnnotationConfigWebApplicationContext context;

    private MockMvc mvc;

    private AuthFlowService auth;

    private TokenService tokens;

    @BeforeEach
    void createOnlyTheMvcAndSecurityContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        auth = context.getBean(AuthFlowService.class);
        tokens = context.getBean(TokenService.class);
        when(tokens.authenticate("expired-session")).thenThrow(new ApiException(401, "Expired access token"));
        when(auth.forgotPassword(any(ForgotPasswordRequest.class), anyString()))
            .thenReturn(Map.of("message", "Recovery email requested"));
        when(auth.resetPassword(any(ResetPasswordRequest.class), anyString()))
            .thenReturn(Map.of("message", "Password reset completed"));
    }

    @AfterEach
    void closeTheIsolatedContext() {
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @MethodSource("validRecoveryRequests")
    void anonymousUsersCanRequestAndCompletePasswordRecovery(
        String path,
        String body)
        throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.message").isString());

        verifyRecoveryCall(path);
        verifyNoInteractions(tokens);
    }

    @ParameterizedTest
    @MethodSource("validRecoveryRequests")
    void expiredAccessSessionsDoNotBlockPasswordRecovery(
        String path,
        String body)
        throws Exception {
        mvc.perform(
            post(path).header("Authorization", "Bearer expired-session").contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.message").isString());

        verifyRecoveryCall(path);
        verifyNoInteractions(tokens);
    }

    @ParameterizedTest
    @MethodSource("invalidRecoveryRequests")
    void malformedOrInvalidRecoveryBodiesStillReturnBadRequestWithAnExpiredSession(
        String path,
        String body)
        throws Exception {
        mvc.perform(
            post(path).header("Authorization", "Bearer expired-session").contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(auth, tokens);
    }

    @ParameterizedTest
    @ValueSource(strings = {FORGOT_PASSWORD, RESET_PASSWORD})
    void recoveryExceptionsApplyOnlyToPostRequests(
        String path)
        throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer expired-session")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid or expired session"));

        verify(tokens).authenticate("expired-session");
        verifyNoInteractions(auth);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"/api/v1/auth/logout", "/api/v1/auth/forgot-password/extra", "/api/v1/auth/reset-password/extra"}
    )
    void otherAuthPathsRemainProtectedAgainstAnonymousAndExpiredSessions(
        String path)
        throws Exception {
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", "Bearer expired-session")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid or expired session"));

        verify(tokens).authenticate("expired-session");
        verifyNoInteractions(auth);
    }

    @Test
    void accountEndpointStillRequiresAValidSession() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Authentication required"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer expired-session"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid or expired session"));

        verify(tokens).authenticate("expired-session");
        verifyNoInteractions(auth);
    }

    private void verifyRecoveryCall(
        String path) {
        if (FORGOT_PASSWORD.equals(path)) {
            verify(auth).forgotPassword(eq(new ForgotPasswordRequest(EMAIL)), anyString());
        } else {
            verify(auth).resetPassword(eq(new ResetPasswordRequest(PASSWORD, PASSWORD, TOKEN)), anyString());
        }
        verifyNoMoreInteractions(auth);
    }

    private static Stream<Arguments> validRecoveryRequests() {
        return Stream.of(
            Arguments.of(FORGOT_PASSWORD, "{\"email\":\"" + EMAIL + "\"}"),
            Arguments.of(
                RESET_PASSWORD,
                "{\"newPassword\":\"" + PASSWORD + "\",\"newPasswordConfirmation\":\"" + PASSWORD + "\",\"token\":\""
                    + TOKEN + "\"}"
            )
        );
    }

    private static Stream<Arguments> invalidRecoveryRequests() {
        return Stream.of(
            Arguments.of(FORGOT_PASSWORD, "{\"email\":\"not-an-email\"}"),
            Arguments.of(FORGOT_PASSWORD, "{"),
            Arguments.of(RESET_PASSWORD, "{"),
            Arguments.of(
                RESET_PASSWORD,
                "{\"newPassword\":\"" + PASSWORD + "\",\"newPasswordConfirmation\":\"" + PASSWORD + "\",\"token\":\"\"}"
            )
        );
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, BearerFilter.class, AuthController.class, ApiExceptionHandler.class})
    static class MvcConfiguration {

        @Bean
        TokenService tokens() {
            return mock(TokenService.class);
        }

        @Bean
        AuthFlowService auth() {
            return mock(AuthFlowService.class);
        }
    }
}
