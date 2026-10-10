package com.example.intelligent_cv_matching_ai_agent.repository.guest.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by GuestSessionService. */
@Repository
public class GuestSessionRepository {

    private final SqlStore db;

    public GuestSessionRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertGuestSessions(
        Map<String, Object> fields) {
        return db.insert("guest_sessions", fields);
    }

    public List<Map<String, Object>> listResumesByIsDemo(
        Object... args) {
        return db.list(
            ("SELECT id,title,language,structured_data FROM resumes WHERE "
                + "is_demo=true AND status='READY' ORDER BY title LIMIT 20"),
            args
        );
    }

    public List<Map<String, Object>> listResumeTemplatesByIsActive(
        Object... args) {
        return db.list("SELECT * FROM resume_templates WHERE is_active AND is_trial_enabled ORDER BY name", args);
    }

}
