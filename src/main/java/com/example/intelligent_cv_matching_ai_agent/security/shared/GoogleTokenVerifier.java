package com.example.intelligent_cv_matching_ai_agent.security.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.time.Instant;

/**
 * Google signatures, timestamps, issuer, application audience and verified
 * email are all checked.
 */
@Component
public class GoogleTokenVerifier {

    private final String clientId;

    private volatile JwtDecoder decoder;

    @Autowired
    public GoogleTokenVerifier(
        @Value("${app.auth.google-client-id:}")
        String clientId) {
        this.clientId = clientId;
    }

    GoogleTokenVerifier(
        String clientId,
        JwtDecoder decoder) {
        this.clientId = clientId;
        this.decoder = decoder;
    }

    private JwtDecoder decoder() {
        if (decoder == null)
            synchronized (this) {
                if (decoder == null) {
                    NimbusJwtDecoder google =
                        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
                    google.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"));
                    decoder = google;
                }
            }

        return decoder;
    }

    public Jwt verify(
        String raw) {
        if (clientId == null || clientId.isBlank())
            throw new ApiException(503, "Google sign-in is not configured");

        Jwt token;

        try {
            token = decoder().decode(raw);
        } catch (JwtException ex) {
            throw new ApiException(401, "Invalid Google ID token");
        }

        if (token.getIssuer() == null || !"https://accounts.google.com".equals(token.getIssuer().toString())
            || !token.getAudience().contains(clientId) || token.getExpiresAt() == null
            || !token.getExpiresAt().isAfter(Instant.now()) || token.getSubject() == null
            || token.getSubject().length() > 255 || token.getSubject().isBlank()
            || !Boolean.TRUE.equals(token.getClaim("email_verified")))
            throw new ApiException(401, "Google ID token claims are invalid");
        if (token.getAudience().size() > 1 && !clientId.equals(token.getClaimAsString("azp")))
            throw new ApiException(401, "Google token authorized party mismatch");

        String email = token.getClaimAsString("email");
        if (email == null || email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new ApiException(401, "Google account email is unavailable");

        return token;
    }

    public boolean authoritativeEmail(
        Jwt jwt) {
        return jwt.getClaimAsString("email").toLowerCase(java.util.Locale.ROOT).endsWith("@gmail.com")
            || jwt.getClaimAsString("hd") != null;
    }
}
