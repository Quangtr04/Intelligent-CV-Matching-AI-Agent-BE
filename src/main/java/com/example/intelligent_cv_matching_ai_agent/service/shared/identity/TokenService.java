package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque bearer sessions: no signing key, and revocation takes effect
 * immediately.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TokenRepository db;

    public TokenService(
        TokenRepository db) {
        this.db = db;
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

        String access = random("a_");
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
        if (access == null || !access.startsWith("a_") || access.length() != 45)
            throw new ApiException(401, "Invalid access token");

        return db.findRefreshTokensByTokenHash(hash(access))
            .orElseThrow(() -> new ApiException(401, "Session expired or account unavailable"));
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
}
