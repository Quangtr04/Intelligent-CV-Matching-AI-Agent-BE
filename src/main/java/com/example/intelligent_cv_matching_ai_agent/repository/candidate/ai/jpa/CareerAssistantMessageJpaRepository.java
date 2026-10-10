package com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.ai.CareerAssistantMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for career_assistant_messages. */
public interface CareerAssistantMessageJpaRepository extends JpaRepository<CareerAssistantMessage, UUID> {
}
