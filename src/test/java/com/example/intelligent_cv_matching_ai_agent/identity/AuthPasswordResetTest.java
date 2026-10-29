package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ResetPasswordRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.AuthRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ResetPasswordTokenRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthPasswordResetTest {

    private static final String EMAIL = "candidate@example.invalid";

    private static final String PASSWORD = "new-fixture-password";

    private static final String RESET_URL =
        "https://frontend.example.invalid/reset-password?source=login&lang=vi#reset";

    private final AuthRepository repository = mock(AuthRepository.class);

    private final ResetPasswordTokenRepository resets = mock(ResetPasswordTokenRepository.class);

    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private final TokenService sessions = mock(TokenService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> mail = mock(ObjectProvider.class);

    private final JavaMailSender sender = mock(JavaMailSender.class);

    private final UUID userId = UUID.randomUUID();

    private final UUID tokenId = UUID.randomUUID();

    @BeforeEach
    void fixtures() {
        when(repository.findUsersByEmailForPasswordReset(anyString()))
            .thenReturn(Optional.of(account("ACTIVE", "EMAIL")));
        when(repository.findResetPasswordTokensByUserId(userId)).thenReturn(Optional.empty());
        when(repository.updateUsersPasswordById(anyString(), eq(userId))).thenReturn(1);
        when(encoder.encode(any(CharSequence.class))).thenAnswer(call -> "encoded:" + call.getArgument(0));
        when(mail.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        when(resets.findResetPasswordTokensByTokenHash2(anyString()))
            .thenReturn(Optional.of(Map.of("user_id", userId)));
        when(resets.findUsersByIdForUpdate(userId)).thenReturn(Optional.of(Map.of("id", userId)));
        when(resets.findResetPasswordTokensByTokenHashForUpdate(anyString()))
            .thenReturn(Optional.of(Map.of("id", tokenId, "user_id", userId)));
        when(resets.updateResetPasswordTokensById(tokenId, userId)).thenReturn(1);
    }

    @Test
    void developmentRecoveryStoresOnlyAHighEntropyHashAndReturnsTheRawTokenLocally() {
        Map<String, Object> response = service(true, false).forgotPassword(" CANDIDATE@EXAMPLE.INVALID ");
        String token = response.get("developmentToken").toString();
        assertTrue(token.matches("p_[A-Za-z0-9_-]{43}"));
        Map<String, Object> stored = storedToken();
        assertEquals(TokenService.hash(token), stored.get("token_hash"));
        assertFalse(stored.containsKey("code_hash"));
        assertFalse(stored.containsValue(token));
        assertEquals(userId, stored.get("user_id"));
        assertEquals(
            Duration.ofMinutes(10),
            Duration.between((OffsetDateTime) stored.get("created_at"), (OffsetDateTime) stored.get("expires_at"))
        );
        assertEquals(600, response.get("expiresIn"));
        verify(repository).findUsersByEmailForPasswordReset(EMAIL);
        verify(repository).updateResetPasswordTokensByUserId(userId);
        verifyNoInteractions(mail, sender, sessions);
    }

    @Test
    void recoveryEmailContainsTheSameTokenAsTheStoredHashAndPreservesUrlQueryAndFragment() throws Exception {
        Map<String, Object> response = service(false, true).forgotPassword(EMAIL);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        MimeMessage message = sent.getValue();
        message.saveChanges();
        String html = body(message, "text/html");
        String plain = body(message, "text/plain");
        String link = Jsoup.parse(html).selectFirst("a[href]").attr("href");
        URI uri = URI.create(link);
        String token = uri.getQuery().substring(uri.getQuery().indexOf("token=") + 6);
        assertTrue(token.matches("p_[A-Za-z0-9_-]{43}"));
        assertEquals("reset", uri.getFragment());
        assertTrue(uri.getQuery().contains("source=login&lang=vi"));
        assertTrue(html.contains("&amp;"));
        assertFalse(html.contains("{{resetLink}}"));
        assertTrue(plain.contains(link));
        assertEquals(TokenService.hash(token), storedToken().get("token_hash"));
        assertFalse(response.containsKey("developmentToken"));
        assertFalse(response.toString().contains(token));
        assertFalse(message.getSubject().contains(token));
    }

    @Test
    void requestingAgainWithinTheCooldownDoesNotReplaceTheCurrentLink() {
        when(repository.findResetPasswordTokensByUserId(userId)).thenReturn(Optional.of(Map.of("id", tokenId)));
        ApiException error = assertThrows(ApiException.class, () -> service(true, false).forgotPassword(EMAIL));
        assertEquals(429, error.status());
        verify(repository, never()).updateResetPasswordTokensByUserId(any());
        verify(repository, never()).insertResetPasswordTokens(anyMap());
        verifyNoInteractions(mail, sender);
    }

    @Test
    void unavailableAndNonEmailAccountsCannotReceivePasswordResetTokens() {
        AuthService service = service(true, false);
        for (Map<String, Object> account : List
            .of(account("PENDING", "EMAIL"), account("ACTIVE", "GOOGLE"), account("SUSPENDED", "EMAIL"))) {
            when(repository.findUsersByEmailForPasswordReset(EMAIL)).thenReturn(Optional.of(account));
            assertEquals(400, assertThrows(ApiException.class, () -> service.forgotPassword(EMAIL)).status());
        }
        when(repository.findUsersByEmailForPasswordReset(EMAIL)).thenReturn(Optional.empty());
        assertEquals(400, assertThrows(ApiException.class, () -> service.forgotPassword(EMAIL)).status());
        verify(repository, never()).insertResetPasswordTokens(anyMap());
        verifyNoInteractions(mail, sender);
    }

    @Test
    void smtpFailureReturnsUnavailableWithoutLeakingProviderDetails() {
        doThrow(new MailSendException("PRIVATE_SMTP_DIAGNOSTIC")).when(sender).send(any(MimeMessage.class));
        ApiException error = assertThrows(ApiException.class, () -> service(false, true).forgotPassword(EMAIL));
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains("PRIVATE_SMTP_DIAGNOSTIC"));
    }

    @Test
    void resetHashesTheIncomingTokenLocksTheAccountFirstAndRevokesSessionsAfterUpdatingItsPassword() {
        String token = TokenService.random("p_");
        Map<String, Object> response = service(true, false).resetPassword(token, PASSWORD, PASSWORD);
        String hash = TokenService.hash(token);
        var ordered = inOrder(resets, repository, sessions);
        ordered.verify(resets).findResetPasswordTokensByTokenHash2(hash);
        ordered.verify(resets).findUsersByIdForUpdate(userId);
        ordered.verify(resets).findResetPasswordTokensByTokenHashForUpdate(hash);
        ordered.verify(resets).updateResetPasswordTokensById(tokenId, userId);
        ordered.verify(repository).updateUsersPasswordById("encoded:" + PASSWORD, userId);
        ordered.verify(resets).updateResetPasswordTokensByUserId(userId);
        ordered.verify(sessions).revokeAll(userId);
        assertEquals("Password reset successfully; you can log in", response.get("message"));
    }

    @Test
    void mismatchedPasswordsAreRejectedBeforeLookingUpOrConsumingTheToken() {
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true, false).resetPassword(TokenService.random("p_"), PASSWORD, "different-password")
        );
        assertEquals(400, error.status());
        verifyNoInteractions(resets, sessions);
        verify(repository, never()).updateUsersPasswordById(anyString(), any());
    }

    @Test
    void invalidExpiredAndPreviouslyConsumedTokensCannotChangeThePassword() {
        AuthService service = service(true, false);
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.resetPassword("123456", PASSWORD, PASSWORD)).status()
        );
        String token = TokenService.random("p_");
        when(resets.findResetPasswordTokensByTokenHash2(TokenService.hash(token))).thenReturn(Optional.empty());
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.resetPassword(token, PASSWORD, PASSWORD)).status()
        );
        verify(resets, never()).findUsersByIdForUpdate(any());
        verify(repository, never()).updateUsersPasswordById(anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void tokenExpiringWhileWaitingForTheLockCannotUpdateThePasswordOrSessions() {
        when(resets.updateResetPasswordTokensById(tokenId, userId)).thenReturn(0);
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true, false).resetPassword(TokenService.random("p_"), PASSWORD, PASSWORD)
        );
        assertEquals(400, error.status());
        verify(repository, never()).updateUsersPasswordById(anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void suspendedAccountCannotRedeemAnOtherwiseValidToken() {
        when(resets.findUsersByIdForUpdate(userId)).thenReturn(Optional.empty());
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true, false).resetPassword(TokenService.random("p_"), PASSWORD, PASSWORD)
        );
        assertEquals(400, error.status());
        verify(resets, never()).findResetPasswordTokensByTokenHashForUpdate(anyString());
        verify(repository, never()).updateUsersPasswordById(anyString(), any());
        verifyNoInteractions(sessions);
    }

    @Test
    void newPasswordsRespectTheUtf8ByteLimitAndRequestLoggingDoesNotExposeSecrets() {
        String oversized = "é".repeat(40);
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true, false).resetPassword(TokenService.random("p_"), oversized, oversized)
        );
        assertEquals(400, error.status());
        verifyNoInteractions(resets, sessions);
        assertEquals(
            "ResetPasswordRequest[redacted]",
            new ResetPasswordRequest(PASSWORD, PASSWORD, TokenService.random("p_")).toString()
        );
    }

    private Map<String, Object> account(
        String status,
        String provider) {
        return new LinkedHashMap<>(Map.of("id", userId, "email", EMAIL, "status", status, "auth_provider", provider));
    }

    private AuthService service(
        boolean developmentToken,
        boolean mailEnabled) {
        return new AuthService(
            repository,
            resets,
            encoder,
            sessions,
            mail,
            developmentToken,
            "noreply@example.invalid",
            RESET_URL,
            mailEnabled
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> storedToken() {
        ArgumentCaptor<Map<String, Object>> stored = ArgumentCaptor.forClass((Class) Map.class);
        verify(repository).insertResetPasswordTokens(stored.capture());
        return stored.getValue();
    }

    private String body(
        Part part,
        String mimeType)
        throws Exception {
        if (part.isMimeType(mimeType))
            return part.getContent().toString();
        if (part.getContent() instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                String content = body(multipart.getBodyPart(index), mimeType);
                if (!content.isEmpty())
                    return content;
            }
        }
        return "";
    }
}
