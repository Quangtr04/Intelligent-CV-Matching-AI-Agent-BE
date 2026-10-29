package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

@Repository
public class ResetPasswordTokenRepository {

    private final SqlStore db;

    public ResetPasswordTokenRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertResetPasswordTokens(
        Map<String, Object> fields) {
        return db.insert("reset_password_tokens", fields);
    }

    public Optional<Map<String, Object>> findUsersByIdForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT id FROM users WHERE id=? AND status='ACTIVE' AND auth_provider='EMAIL' "
                + "AND deleted_at IS NULL FOR NO KEY UPDATE"),
            args
        );
    }

    public Optional<Map<String, Object>> findResetPasswordTokensByTokenHash(
        Object... args) {
        return db.optional(
            ("SELECT u.id,u.role,u.full_name FROM reset_password_tokens t JOIN users u ON "
                + "u.id=t.user_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND "
                + "t.expires_at>now() AND u.status='ACTIVE' AND u.deleted_at IS NULL"),
            args
        );
    }

    public Optional<Map<String, Object>> findResetPasswordTokensByTokenHash2(
        Object... args) {
        return db.optional(
            ("SELECT user_id FROM reset_password_tokens WHERE token_hash=? AND revoked_at "
                + "IS NULL AND expires_at>now()"),
            args
        );
    }

    public Optional<Map<String, Object>> findResetPasswordTokensByTokenHashForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT t.id,t.user_id FROM reset_password_tokens t JOIN users u ON "
                + "u.id=t.user_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND "
                + "t.expires_at>now() AND u.status='ACTIVE' AND u.deleted_at IS NULL FOR " + "UPDATE OF t"),
            args
        );
    }

    public int updateResetPasswordTokensById(
        UUID tokenId,
        UUID userId) {
        return db.update(
            "UPDATE reset_password_tokens SET revoked_at=now() WHERE id=? AND user_id=? "
                + "AND revoked_at IS NULL AND expires_at>clock_timestamp()",
            tokenId,
            userId
        );
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", args);
    }

    public int updateResetPasswordTokensByUserId(
        Object... args) {
        return db
            .update("UPDATE reset_password_tokens SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", args);
    }

}
