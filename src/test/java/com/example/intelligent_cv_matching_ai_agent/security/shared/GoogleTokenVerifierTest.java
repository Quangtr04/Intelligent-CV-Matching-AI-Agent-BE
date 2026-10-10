package com.example.intelligent_cv_matching_ai_agent.security.shared;

import com.example.intelligent_cv_matching_ai_agent.security.shared.GoogleTokenVerifier;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleTokenVerifierTest {

    private Jwt token(
        String audience,
        boolean verified,
        String email) {
        return Jwt.withTokenValue("signed-token").header("alg", "RS256").subject("google-subject")
            .issuer("https://accounts.google.com").audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(30))
            .expiresAt(Instant.now().plusSeconds(300)).claim("email", email).claim("email_verified", verified).build();
    }

    @Test
    void rejectsTokenIssuedForDifferentApplication() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("raw")).thenReturn(token("other-client", true, "person@gmail.com"));
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new GoogleTokenVerifier("our-client", decoder).verify("raw"))
                .status()
        );
    }

    @Test
    void rejectsUnverifiedEmail() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("raw")).thenReturn(token("our-client", false, "person@gmail.com"));
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new GoogleTokenVerifier("our-client", decoder).verify("raw"))
                .status()
        );
    }

    @Test
    void thirdPartyEmailRequiresOurOwnEmailVerification() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt token = token("our-client", true, "person@example.org");
        when(decoder.decode("raw")).thenReturn(token);
        GoogleTokenVerifier verifier = new GoogleTokenVerifier("our-client", decoder);
        assertEquals(token, verifier.verify("raw"));
        assertFalse(verifier.authoritativeEmail(token));
    }

    @Test
    void acceptsVerifiedGmailForConfiguredApplication() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt token = token("our-client", true, "person@gmail.com");
        when(decoder.decode("raw")).thenReturn(token);
        GoogleTokenVerifier verifier = new GoogleTokenVerifier("our-client", decoder);
        assertEquals(token, verifier.verify("raw"));
        assertTrue(verifier.authoritativeEmail(token));
    }
}
