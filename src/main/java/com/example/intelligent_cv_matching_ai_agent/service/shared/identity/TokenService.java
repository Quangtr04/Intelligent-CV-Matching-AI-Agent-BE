package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final TokenRepository db;

    private final String jwtSecret;

    @Autowired
    public TokenService(
        TokenRepository db,
        @Value("${app.security.jwt-secret:development-only-change-me-to-a-long-random-secret}")
        String jwtSecret) {
        this.db = db;
        this.jwtSecret = jwtSecret;
    }

    public TokenService(
        TokenRepository db) {
        this(db, "development-only-change-me-to-a-long-random-secret");
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

        String access = jwt(userId, OffsetDateTime.now().plusMinutes(15));
        String refresh = random("r_");

        save(userId, access, OffsetDateTime.now().plusMinutes(15));
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

        UUID tokenUser = verifyJwt(access);
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

        db.updateRefreshTokensById(token.get("id"));

        return issue((UUID) token.get("user_id"));
    }

    @Transactional
    public void revokeAll(
        UUID user) {
        db.getUsersByIdForUpdate(user);

        db.updateRefreshTokensByUserId(user);
    }

    private String jwt(
        UUID userId,
        OffsetDateTime expires) {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"sub\":\"" + userId + "\",\"iat\":" + Instant.now().getEpochSecond() + ",\"exp\":"
            + expires.toEpochSecond() + "}";
        String body = encode(header) + "." + encode(payload);

        return body + "." + sign(body);
    }

    private UUID verifyJwt(
        String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank())
            throw new ApiException(401, "Invalid access token");

        String body = parts[0] + "." + parts[1];
        if (!constantTime(sign(body), parts[2]))
            throw new ApiException(401, "Invalid access token");

        Map<String, String> claims = claims(parts[1]);
        long expiresAt;
        try {
            expiresAt = Long.parseLong(claims.getOrDefault("exp", ""));
        } catch (NumberFormatException ex) {
            throw new ApiException(401, "Invalid access token");
        }

        if (expiresAt <= Instant.now().getEpochSecond())
            throw new ApiException(401, "Invalid access token");

        try {
            return UUID.fromString(claims.get("sub"));
        } catch (RuntimeException ex) {
            throw new ApiException(401, "Invalid access token");
        }
    }

    private String sign(
        String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            return URL_ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(
        String value) {
        return URL_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTime(
        String left,
        String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> claims(
        String payload) {
        String json;
        try {
            json = new String(URL_DECODER.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(401, "Invalid access token");
        }

        Map<String, String> values = new LinkedHashMap<>();
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"([^\"]*)\"|[0-9]+)").matcher(json);
        while (matcher.find())
            values.put(matcher.group(1), matcher.group(3) == null ? matcher.group(2) : matcher.group(3));

        return values;
    }
}
