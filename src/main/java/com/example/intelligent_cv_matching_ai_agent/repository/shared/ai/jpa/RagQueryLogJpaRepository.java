package com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.ai.RagQueryLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for rag_query_logs. */
public interface RagQueryLogJpaRepository extends JpaRepository<RagQueryLog, UUID> {
}
