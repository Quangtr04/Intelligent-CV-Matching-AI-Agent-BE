package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.AiUsageRecorderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class AiUsageRecorder {

    private final AiUsageRecorderRepository db;

    public AiUsageRecorder(
        AiUsageRecorderRepository db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        Map<String, Object> fields) {
        db.insertAiUsageLogs(fields);
    }
}
