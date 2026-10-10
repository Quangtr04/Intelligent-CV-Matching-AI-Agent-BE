package com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.ai.CareerAssistantFeedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for career_assistant_feedback. */
public interface CareerAssistantFeedbackJpaRepository extends JpaRepository<CareerAssistantFeedback, UUID> {
}
