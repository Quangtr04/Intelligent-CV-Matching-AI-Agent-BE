package com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.monitoring.AuditRepository;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditRepository db;

    private final CurrentUser current;

    public AuditService(
        AuditRepository db,
        CurrentUser current) {
        this.db = db;
        this.current = current;
    }

    public void record(
        String action,
        String entity,
        UUID id,
        Object before,
        Object after) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("actor_user_id", current.id());
        log.put("action", action);
        log.put("entity_type", entity);
        log.put("entity_id", id);
        log.put("old_value", db.json(before));
        log.put("new_value", db.json(after));
        log.put("created_at", OffsetDateTime.now());

        db.insertAuditLogs(log);

        if (current.hasRole("ADMIN"))
            db.insertAdminActions(
                Map.of(
                    "admin_user_id",
                    current.id(),
                    "action_type",
                    action,
                    "target_type",
                    entity,
                    "target_id",
                    id,
                    "metadata",
                    db.json(after),
                    "created_at",
                    OffsetDateTime.now()
                )
            );
    }
}
