package com.example.intelligent_cv_matching_ai_agent.repository.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AiService. */
@Repository
public class AiRepository {

    private final SqlStore db;

    public AiRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Optional<Map<String, Object>> findAiConfigurationsByConfigType(
        Object... args) {
        return db.optional(
            ("SELECT model_name FROM ai_configurations WHERE config_type='CHAT' AND "
                + "is_active=true AND lower(provider)=lower(?) ORDER BY version DESC " + "LIMIT 1"),
            args
        );
    }

    public Optional<Map<String, Object>> findAiPromptTemplatesByFeatureCode(
        Object... args) {
        return db.optional(
            ("SELECT system_prompt,user_prompt_template FROM ai_prompt_templates "
                + "WHERE feature_code=? AND is_active=true ORDER BY version DESC LIMIT 1"),
            args
        );
    }

}
