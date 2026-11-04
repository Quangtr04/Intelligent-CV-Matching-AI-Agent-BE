package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.GoogleLoginRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.GoogleTokenVerifier;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.GoogleLoginService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoogleLoginServiceTest {

    private final GoogleLoginRepository db = mock(GoogleLoginRepository.class);

    private final GoogleTokenVerifier verifier = mock(GoogleTokenVerifier.class);

    private final TokenService tokens = mock(TokenService.class);

    private final AuthService auth = mock(AuthService.class);

    private final GoogleLoginService service = new GoogleLoginService(verifier, db, tokens, auth);

    private Jwt googleToken() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("google-identity")
            .claim("email", "Person@example.org").claim("name", "Person").build();
        when(verifier.verify("id-token")).thenReturn(jwt);
        return jwt;
    }

    @Test
    void matchingEmailNeverLinksGoogleTokenToExistingPasswordAccount() {
        googleToken();
        when(db.findUsersByGoogleSub("google-identity")).thenReturn(Optional.empty());
        when(db.findUsersByEmail("person@example.org")).thenReturn(Optional.of(Map.of("id", UUID.randomUUID())));

        assertEquals(409, assertThrows(ApiException.class, () -> service.login("id-token")).status());

        verifyNoInteractions(tokens, auth);
        verify(db, never()).insertUsers(anyMap());
    }

    @Test
    void newNonAuthoritativeEmailCreatesPendingCandidateAndReturnsVerificationMetadata() {
        googleToken();
        UUID id = UUID.randomUUID();
        when(db.insertUsers(anyMap())).thenReturn(id);
        when(auth.resend("person@example.org"))
            .thenReturn(Map.of("message", "Code sent", "expiresIn", 600, "developmentCode", "123456"));

        Map<String, Object> response = service.login("id-token");

        assertEquals(true, response.get("emailVerificationRequired"));
        assertEquals("person@example.org", response.get("email"));
        assertEquals(true, response.get("verificationCodeSent"));
        assertEquals("123456", response.get("developmentCode"));
        assertEquals(600, response.get("expiresIn"));
        assertEquals("Code sent", response.get("message"));
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.captor();
        verify(db).insertUsers(fields.capture());
        assertEquals("PENDING", fields.getValue().get("status"));
        assertEquals("CANDIDATE", fields.getValue().get("role"));
        assertEquals("GOOGLE", fields.getValue().get("auth_provider"));
        assertFalse(fields.getValue().containsKey("email_verified_at"));
        verify(db).updateCandidateProfiles(id);
        verify(auth).resend("person@example.org");
        verify(db, never()).updateUsersById(any());
        verifyNoInteractions(tokens);
    }

    @Test
    void existingPendingAccountReturnsStoredEmailWithoutSendingAnotherCodeOrIssuingTokens() {
        googleToken();
        UUID id = UUID.randomUUID();
        when(db.findUsersByGoogleSub("google-identity"))
            .thenReturn(Optional.of(Map.of("id", id, "email", "stored@example.org", "status", "PENDING")));

        Map<String, Object> response = service.login("id-token");

        assertEquals(true, response.get("emailVerificationRequired"));
        assertEquals("stored@example.org", response.get("email"));
        assertEquals(false, response.get("verificationCodeSent"));
        assertFalse(response.containsKey("developmentCode"));
        verifyNoInteractions(tokens, auth);
        verify(db, never()).updateUsersById(any());
        verify(db, never()).insertUsers(anyMap());
    }

    @Test
    void newAuthoritativeEmailCreatesActiveCandidateAndIssuesSession() {
        Jwt jwt = googleToken();
        UUID id = UUID.randomUUID();
        Map<String, Object> session = Map.of("accessToken", "access", "refreshToken", "refresh");
        when(verifier.authoritativeEmail(jwt)).thenReturn(true);
        when(db.insertUsers(anyMap())).thenReturn(id);
        when(tokens.issue(id)).thenReturn(session);

        assertSame(session, service.login("id-token"));

        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.captor();
        verify(db).insertUsers(fields.capture());
        assertEquals("ACTIVE", fields.getValue().get("status"));
        assertEquals("CANDIDATE", fields.getValue().get("role"));
        assertNotNull(fields.getValue().get("email_verified_at"));
        verify(db).updateCandidateProfiles(id);
        verify(db).updateUsersById(id);
        verify(tokens).issue(id);
        verifyNoInteractions(auth);
    }

    @Test
    void existingActiveGoogleAccountIssuesSession() {
        googleToken();
        UUID id = UUID.randomUUID();
        Map<String, Object> session = Map.of("accessToken", "access", "refreshToken", "refresh");
        when(db.findUsersByGoogleSub("google-identity"))
            .thenReturn(Optional.of(Map.of("id", id, "email", "person@example.org", "status", "ACTIVE")));
        when(tokens.issue(id)).thenReturn(session);

        assertSame(session, service.login("id-token"));

        verify(db).updateUsersById(id);
        verify(tokens).issue(id);
        verifyNoInteractions(auth);
        verify(db, never()).insertUsers(anyMap());
    }

    @Test
    void suspendedGoogleAccountCannotCreateSessionOrReceiveVerificationCode() {
        googleToken();
        when(db.findUsersByGoogleSub("google-identity"))
            .thenReturn(Optional.of(Map.of("id", UUID.randomUUID(), "status", "SUSPENDED")));

        assertEquals(403, assertThrows(ApiException.class, () -> service.login("id-token")).status());

        verifyNoInteractions(tokens, auth);
        verify(db, never()).updateUsersById(any());
    }
}
