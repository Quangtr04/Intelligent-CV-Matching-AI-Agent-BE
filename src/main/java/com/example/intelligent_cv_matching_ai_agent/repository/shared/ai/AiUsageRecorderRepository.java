package com.example.intelligent_cv_matching_ai_agent.repository.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AiUsageRecorder. */
@Repository
public class AiUsageRecorderRepository {

    private final SqlStore db;

    public AiUsageRecorderRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertAiUsageLogs(
        Map<String, Object> fields) {
        return db.insert("ai_usage_logs", fields);
    }

}
