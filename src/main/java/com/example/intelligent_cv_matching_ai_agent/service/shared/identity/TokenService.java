package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * JWT access tokens with database-backed token hashes. Authentication verifies
 * the JWT signature and still checks the token/account state so revocation,
 * suspensions and soft deletes take effect before accepting a bearer token.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenRepository db;

    private final JwtEncoder encoder;

    private final JwtDecoder decoder;

    public TokenService(
        TokenRepository db,
        @Value("${app.security.jwt-secret}")
        String jwtSecret) {
        this.db = db;
        SecretKeySpec key = signingKey(jwtSecret);
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new JwtTimestampValidator(Duration.ZERO)));
        this.decoder = jwtDecoder;
    }

    private static SecretKeySpec signingKey(
        String secret) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("APP_JWT_SECRET must contain at least 32 random UTF-8 bytes");
        }
        String normalized = secret.strip().toLowerCase(Locale.ROOT);
        if (normalized.contains("change-me") || normalized.contains("changeme") || normalized.startsWith("replace-")
            || secret.contains("${")) {
            throw new IllegalArgumentException("APP_JWT_SECRET must be a generated secret, not a placeholder");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public static String random(
        String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(
        String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional
    public Map<String, Object> issue(
        UUID userId) {
        requireActive(userId);

        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(900);
        String access = encodeAccessToken(userId, issuedAt, expiresAt);
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

        UUID tokenUser = decodeAccessToken(access);
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

    private String encodeAccessToken(
        UUID userId,
        Instant issuedAt,
        Instant expiresAt) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId.toString()).id(UUID.randomUUID().toString())
            .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private UUID decodeAccessToken(
        String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Instant now = Instant.now();
            if (jwt.getSubject() == null || jwt.getId() == null || jwt.getId().isBlank() || jwt.getIssuedAt() == null
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now) || jwt.getIssuedAt().isAfter(now)
                || !jwt.getIssuedAt().isBefore(jwt.getExpiresAt())) {
                throw new ApiException(401, "Invalid access token");
            }
            return UUID.fromString(jwt.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ApiException(401, "Invalid access token");
        }
    }
}
