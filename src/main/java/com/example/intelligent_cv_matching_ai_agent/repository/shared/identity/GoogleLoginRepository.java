package com.example.intelligent_cv_matching_ai_agent.repository.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by GoogleLoginService. */
@Repository
public class GoogleLoginRepository {

    private final SqlStore db;

    public GoogleLoginRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Optional<Map<String, Object>> findUsersByGoogleSub(
        Object... args) {
        return db.optional("SELECT id,email,status FROM users WHERE google_sub=? AND auth_provider='GOOGLE'", args);
    }

    public Optional<Map<String, Object>> findUsersByEmail(
        Object... args) {
        return db.optional("SELECT id FROM users WHERE email=?", args);
    }

    public UUID insertUsers(
        Map<String, Object> fields) {
        return db.insert("users", fields);
    }

    public int updateCandidateProfiles(
        Object... args) {
        return db.update(
            ("INSERT INTO candidate_profiles(user_id,created_at,updated_at) VALUES " + "(?,now(),now())"),
            args
        );
    }

    public int updateUsersById(
        Object... args) {
        return db.update("UPDATE users SET last_login_at=now(),updated_at=now() WHERE id=?", args);
    }

}
