package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by TokenService. */
@Repository
public class TokenRepository {

    private final SqlStore db;

    public TokenRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertRefreshTokens(
        Map<String, Object> fields) {
        return db.insert("refresh_tokens", fields);
    }

    public Optional<Map<String, Object>> findUsersByIdForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT id FROM users WHERE id=? AND status='ACTIVE' AND deleted_at IS " + "NULL FOR NO KEY UPDATE"),
            args
        );
    }

    public Optional<Map<String, Object>> findRefreshTokensByTokenHash(
        Object... args) {
        return db.optional(
            ("SELECT u.id,u.role,u.full_name FROM refresh_tokens t JOIN users u ON "
                + "u.id=t.user_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND "
                + "t.expires_at>now() AND u.status='ACTIVE' AND u.deleted_at IS NULL"),
            args
        );
    }

    public Optional<Map<String, Object>> findRefreshTokensByTokenHash2(
        Object... args) {
        return db.optional(
            ("SELECT user_id FROM refresh_tokens WHERE token_hash=? AND revoked_at " + "IS NULL AND expires_at>now()"),
            args
        );
    }

    public Optional<Map<String, Object>> findRefreshTokensByTokenHashForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT t.id,t.user_id FROM refresh_tokens t JOIN users u ON "
                + "u.id=t.user_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND "
                + "t.expires_at>now() AND u.status='ACTIVE' AND u.deleted_at IS NULL FOR " + "UPDATE OF t"),
            args
        );
    }

    public int updateRefreshTokensById(
        UUID tokenId,
        UUID userId) {
        return db.update(
            "UPDATE refresh_tokens SET revoked_at=now() WHERE id=? AND user_id=? "
                + "AND revoked_at IS NULL AND expires_at>clock_timestamp()",
            tokenId,
            userId
        );
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", args);
    }

    public int updateRefreshTokensByUserId(
        Object... args) {
        return db.update("UPDATE refresh_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", args);
    }

}
