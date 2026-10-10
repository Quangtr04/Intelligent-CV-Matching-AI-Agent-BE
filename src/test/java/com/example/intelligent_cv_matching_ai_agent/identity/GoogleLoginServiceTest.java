package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.GoogleLoginRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.GoogleLoginService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.GoogleTokenVerifier;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoogleLoginServiceTest {

    @Test
    void matchingEmailNeverLinksGoogleTokenToExistingPasswordAccount() {
        SqlStore db = mock(SqlStore.class);
        GoogleTokenVerifier verifier = mock(GoogleTokenVerifier.class);
        TokenService tokens = mock(TokenService.class);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("new-google-identity")
            .claim("email", "Person@example.org").build();
        when(verifier.verify("id-token")).thenReturn(jwt);
        when(db.optional(contains("google_sub=?"), eq("new-google-identity"))).thenReturn(Optional.empty());
        when(db.optional(contains("email=?"), eq("person@example.org")))
            .thenReturn(Optional.of(Map.of("id", UUID.randomUUID())));
        var service = new GoogleLoginService(verifier, new GoogleLoginRepository(db), tokens, mock(AuthService.class));
        assertEquals(409, assertThrows(ApiException.class, () -> service.login("id-token")).status());
        verifyNoInteractions(tokens);
        verify(db, never()).insert(anyString(), anyMap());
    }
}
