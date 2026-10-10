package com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.ai.AiUsageLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for ai_usage_logs. */
public interface AiUsageLogJpaRepository extends JpaRepository<AiUsageLog, UUID> {
}
