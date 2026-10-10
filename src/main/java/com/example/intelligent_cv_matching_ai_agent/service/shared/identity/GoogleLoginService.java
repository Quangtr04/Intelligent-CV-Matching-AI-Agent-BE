package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.security.shared.GoogleTokenVerifier;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.GoogleLoginRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GoogleLoginService {

    private final GoogleTokenVerifier verifier;

    private final GoogleLoginRepository db;

    private final TokenService sessions;

    private final AuthService auth;

    public GoogleLoginService(
        GoogleTokenVerifier verifier,
        GoogleLoginRepository db,
        TokenService sessions,
        AuthService auth) {
        this.verifier = verifier;
        this.db = db;
        this.sessions = sessions;
        this.auth = auth;
    }

    @Transactional
    public Map<String, Object> login(
        String idToken) {
        Jwt jwt = verifier.verify(idToken);
        String email = AuthService.email(jwt.getClaimAsString("email"));
        var existing = db.findUsersByGoogleSub(jwt.getSubject());
        UUID id;

        if (existing.isEmpty()) {
            // Never merge accounts merely because an external ID token claims the same
            // email.
            if (db.findUsersByEmail(email).isPresent())
                throw ApiException.conflict("This email already has an account; use its existing sign-in method");

            OffsetDateTime now = OffsetDateTime.now();
            boolean authoritative = verifier.authoritativeEmail(jwt);
            String name = jwt.getClaimAsString("name");

            if (name == null || name.isBlank())
                name = email;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("email", email);
            fields.put("google_sub", jwt.getSubject());
            fields.put("auth_provider", "GOOGLE");
            fields.put("full_name", name.substring(0, Math.min(name.length(), 255)));
            fields.put("role", "CANDIDATE");
            fields.put("status", authoritative ? "ACTIVE" : "PENDING");

            if (authoritative)
                fields.put("email_verified_at", now);

            fields.put("created_at", now);
            fields.put("updated_at", now);

            id = db.insertUsers(fields);
            db.updateCandidateProfiles(id);
            if (!authoritative)
                return auth.resend(email);
        } else {
            id = (UUID) existing.get().get("id");
            String status = existing.get().get("status").toString();
            if ("PENDING".equals(status)) {
                return Map.of(
                    "message",
                    "Verify this account's email before signing in; use /auth/resend-code if needed",
                    "emailVerificationRequired",
                    true
                );
            }
            if (!"ACTIVE".equals(status))
                throw ApiException.forbidden("Google account is suspended or deleted");
        }

        db.updateUsersById(id);

        return sessions.issue(id);
    }
}
