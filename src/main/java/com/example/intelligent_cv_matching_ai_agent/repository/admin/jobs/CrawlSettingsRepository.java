package com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CrawlSettingsRepository {

    private final SqlStore db;

    public CrawlSettingsRepository(
        SqlStore db) {
        this.db = db;
    }

    public Optional<Map<String, Object>> find() {
        return db.optional("SELECT * FROM job_crawl_settings WHERE id=1");
    }

    public Map<String, Object> save(
        boolean enabled,
        Duration fixedDelay,
        UUID actorId,
        List<CrawlSourceSettingsResponse> sources,
        UUID updatedBy) {
        return db.one(
            "INSERT INTO job_crawl_settings(id,enabled,fixed_delay_millis,sources_json,actor_id,updated_by) "
                + "VALUES(1,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET enabled=EXCLUDED.enabled,"
                + "fixed_delay_millis=EXCLUDED.fixed_delay_millis,sources_json=EXCLUDED.sources_json,"
                + "actor_id=EXCLUDED.actor_id,version=job_crawl_settings.version+1,"
                + "updated_by=EXCLUDED.updated_by,updated_at=now() RETURNING *",
            enabled,
            fixedDelay.toMillis(),
            db.json(sources),
            actorId,
            updatedBy
        );
    }

    public void audit(
        UUID actorId) {
        db.insert(
            "audit_logs",
            Map.of("actor_user_id", actorId, "action", "CRAWL_SETTINGS_UPDATED", "entity_type", "job_crawl_settings")
        );
    }
}
