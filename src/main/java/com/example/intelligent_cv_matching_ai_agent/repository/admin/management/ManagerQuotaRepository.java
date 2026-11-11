package com.example.intelligent_cv_matching_ai_agent.repository.admin.management;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ManagerQuotaRepository {

    private final SqlStore db;

    public ManagerQuotaRepository(
        SqlStore db) {
        this.db = db;
    }

    public void requireManager(
        UUID id) {
        db.one("SELECT id FROM users WHERE id=? AND role='MANAGER' AND deleted_at IS NULL", id);
    }

    public Optional<Map<String, Object>> limits(
        UUID id) {
        return db
            .optional("SELECT max_token_quota,max_api_quota FROM manager_quota_limits WHERE manager_user_id=?", id);
    }

    public void save(
        UUID manager,
        long tokens,
        long calls,
        UUID actor) {
        db.update(
            "INSERT INTO manager_quota_limits(manager_user_id,max_token_quota,max_api_quota,updated_by) "
                + "VALUES (?,?,?,?) ON CONFLICT(manager_user_id) DO UPDATE SET "
                + "max_token_quota=EXCLUDED.max_token_quota,max_api_quota=EXCLUDED.max_api_quota,"
                + "updated_by=EXCLUDED.updated_by,updated_at=now()",
            manager,
            tokens,
            calls,
            actor
        );
    }
}
