package com.example.intelligent_cv_matching_ai_agent.repository.guest.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by GuestAccess. */
@Repository
public class GuestAccessRepository {

    private final SqlStore db;

    public GuestAccessRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Optional<Map<String, Object>> findGuestSessionsBySessionKey(
        Object... args) {
        return db.optional(
            ("SELECT * FROM guest_sessions WHERE session_key=? AND "
                + "converted_user_id IS NULL AND expires_at>now()"),
            args
        );
    }

    public int updateGuestSessionsById(
        Object... args) {
        return db.update(
            ("UPDATE guest_sessions SET token_used=token_used+? WHERE id=? AND "
                + "token_used+?<=token_limit AND expires_at>now() AND converted_user_id " + "IS NULL"),
            args
        );
    }

    public UUID insertTrialUsageEvents(
        Map<String, Object> fields) {
        return db.insert("trial_usage_events", fields);
    }

    public Map<String, Object> getTrialUsageEventsByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM trial_usage_events WHERE id=? FOR UPDATE", args);
    }

    public int updateGuestSessionsById2(
        Object... args) {
        return db.update("UPDATE guest_sessions SET token_used=token_used-? WHERE id=? AND token_used>=?", args);
    }

    public int updateTrialUsageEventsById(
        Object... args) {
        return db.update("UPDATE trial_usage_events SET tokens_used=?,status=? WHERE id=?", args);
    }

}
