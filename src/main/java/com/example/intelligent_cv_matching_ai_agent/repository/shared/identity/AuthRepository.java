package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AuthService. */
@Repository
public class AuthRepository {

    private final SqlStore db;

    public AuthRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Optional<Map<String, Object>> findUsersByEmail(
        Object... args) {
        return db.optional("SELECT id FROM users WHERE email=?", args);
    }

    public UUID insertUsers(
        Map<String, Object> fields) {
        return db.insert("users", fields);
    }

    public Optional<Map<String, Object>> findUsersByEmail2(
        Object... args) {
        return db.optional("SELECT id,email FROM users WHERE email=? AND status='PENDING'", args);
    }

    public Optional<Map<String, Object>> findEmailVerificationCodesByUserId(
        Object... args) {
        return db.optional(
            ("SELECT id FROM email_verification_codes WHERE user_id=? AND " + "created_at>now()-interval '60 seconds'"),
            args
        );
    }

    public int updateEmailVerificationCodesByUserId(
        Object... args) {
        return db.update(
            ("UPDATE email_verification_codes SET expires_at=now() WHERE user_id=? " + "AND verified_at IS NULL"),
            args
        );
    }

    public UUID insertEmailVerificationCodes(
        Map<String, Object> fields) {
        return db.insert("email_verification_codes", fields);
    }

    public Optional<Map<String, Object>> findEmailVerificationCodesByEmailForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT c.id,c.user_id,c.code_hash,c.attempt_count FROM "
                + "email_verification_codes c JOIN users u ON u.id=c.user_id WHERE "
                + "u.email=? AND u.status='PENDING' AND c.verified_at IS NULL AND "
                + "c.expires_at>now() ORDER BY c.created_at DESC LIMIT 1 FOR UPDATE OF c"),
            args
        );
    }

    public int updateEmailVerificationCodesById(
        Object... args) {
        return db.update("UPDATE email_verification_codes SET attempt_count=attempt_count+1 WHERE id=?", args);
    }

    public int updateEmailVerificationCodesById2(
        Object... args) {
        return db.update("UPDATE email_verification_codes SET verified_at=now() WHERE id=?", args);
    }

    public int updateUsersById(
        Object... args) {
        return db.update(
            ("UPDATE users SET status='ACTIVE',email_verified_at=now(),"
                + "updated_at=now() WHERE id=? AND status='PENDING'"),
            args
        );
    }

    public int updateCandidateProfiles(
        Object... args) {
        return db.update(
            ("INSERT INTO candidate_profiles(user_id,created_at,updated_at) VALUES "
                + "(?,now(),now()) ON CONFLICT DO NOTHING"),
            args
        );
    }

    public Optional<Map<String, Object>> findUsersByEmailForUpdate(
        Object... args) {
        return db.optional(
            ("SELECT id,password_hash,status FROM users WHERE email=? AND "
                + "auth_provider='EMAIL' AND deleted_at IS NULL FOR NO KEY UPDATE"),
            args
        );
    }

    public int updateUsersById2(
        Object... args) {
        return db.update("UPDATE users SET last_login_at=now(),updated_at=now() WHERE id=?", args);
    }

}
