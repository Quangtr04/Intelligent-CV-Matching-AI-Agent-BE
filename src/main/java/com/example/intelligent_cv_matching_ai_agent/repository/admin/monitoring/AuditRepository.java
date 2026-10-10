package com.example.intelligent_cv_matching_ai_agent.repository.admin.monitoring;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AuditService. */
@Repository
public class AuditRepository {

    private final SqlStore db;

    public AuditRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertAuditLogs(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }

    public UUID insertAdminActions(
        Map<String, Object> fields) {
        return db.insert("admin_actions", fields);
    }

}
