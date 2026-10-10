package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.AssessmentAnswer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for assessment_answers. */
public interface AssessmentAnswerJpaRepository extends JpaRepository<AssessmentAnswer, UUID> {
}
