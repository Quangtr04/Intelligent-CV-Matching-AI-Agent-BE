package com.example.intelligent_cv_matching_ai_agent.util.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

public final class TokenSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenSupport() {
    }

    public static SecretKeySpec signingKey(
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

    public static String encodeAccessToken(
        JwtEncoder encoder,
        UUID userId,
        Instant issuedAt,
        Instant expiresAt) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId.toString()).id(UUID.randomUUID().toString())
            .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public static UUID decodeAccessToken(
        JwtDecoder decoder,
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
