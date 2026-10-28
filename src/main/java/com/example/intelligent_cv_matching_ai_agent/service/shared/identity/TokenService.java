package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import com.example.intelligent_cv_matching_ai_agent.util.shared.identity.TokenSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * JWT access tokens with database-backed token hashes. Authentication verifies
 * the JWT signature and still checks the token/account state so revocation,
 * suspensions and soft deletes take effect before accepting a bearer token.
 */
@Service
public class TokenService {

    private final TokenRepository db;

    private final JwtEncoder encoder;

    private final JwtDecoder decoder;

    public TokenService(
        TokenRepository db,
        @Value("${app.security.jwt-secret}")
        String jwtSecret) {
        this.db = db;
        SecretKeySpec key = TokenSupport.signingKey(jwtSecret);
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new JwtTimestampValidator(Duration.ZERO)));
        this.decoder = jwtDecoder;
    }

    public static String random(
        String prefix) {
        return TokenSupport.random(prefix);
    }

    public static String hash(
        String value) {
        return TokenSupport.hash(value);
    }

    @Transactional
    public Map<String, Object> issue(
        UUID userId) {
        requireActive(userId);

        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(900);
        String access = TokenSupport.encodeAccessToken(encoder, userId, issuedAt, expiresAt);
        String refresh = random("r_");

        save(userId, access, expiresAt.atOffset(java.time.ZoneOffset.UTC));
        save(userId, refresh, OffsetDateTime.now().plusDays(30));

        return Map.of("accessToken", access, "refreshToken", refresh, "tokenType", "Bearer", "expiresIn", 900);
    }

    private void save(
        UUID user,
        String raw,
        OffsetDateTime expires) {
        db.insertRefreshTokens(
            Map.of("user_id", user, "token_hash", hash(raw), "expires_at", expires, "created_at", OffsetDateTime.now())
        );
    }

    private void requireActive(
        UUID user) {
        // Account mutations take the same lock before revoking tokens. This keeps a
        // concurrent
        // sign-in from creating a fresh session after suspension or password
        // replacement.
        db.findUsersByIdForUpdate(user).orElseThrow(() -> new ApiException(401, "Account unavailable"));
    }

    public Map<String, Object> authenticate(
        String access) {
        if (access == null || access.startsWith("r_"))
            throw new ApiException(401, "Invalid access token");

        UUID tokenUser = TokenSupport.decodeAccessToken(decoder, access);
        Map<String, Object> session = db.findRefreshTokensByTokenHash(hash(access))
            .orElseThrow(() -> new ApiException(401, "Session expired or account unavailable"));
        if (!tokenUser.equals(session.get("id")))
            throw new ApiException(401, "Invalid access token");

        return session;
    }

    @Transactional
    public Map<String, Object> rotate(
        String refresh) {
        if (refresh == null || !refresh.startsWith("r_") || refresh.length() != 45)
            throw new ApiException(401, "Invalid refresh token");

        UUID owner = (UUID) db.findRefreshTokensByTokenHash2(hash(refresh))
            .orElseThrow(() -> new ApiException(401, "Refresh token expired or already used")).get("user_id");
        // Always lock the account before a token, matching password changes and
        // revocation.
        requireActive(owner);

        Map<String, Object> token = db.findRefreshTokensByTokenHashForUpdate(hash(refresh))
            .orElseThrow(() -> new ApiException(401, "Refresh token expired or already used"));

        if (!owner.equals(token.get("user_id")))
            throw new ApiException(401, "Invalid refresh token");
        if (db.updateRefreshTokensById((UUID) token.get("id"), owner) != 1)
            throw new ApiException(401, "Refresh token expired or already used");

        return issue(owner);
    }

    @Transactional
    public void revokeAll(
        UUID user) {
        db.getUsersByIdForUpdate(user);

        db.updateRefreshTokensByUserId(user);
    }

}
