package com.example.intelligent_cv_matching_ai_agent.repository.admin.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.ai.AiConfiguration;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for ai_configurations. */
public interface AiConfigurationJpaRepository extends JpaRepository<AiConfiguration, UUID> {
}
