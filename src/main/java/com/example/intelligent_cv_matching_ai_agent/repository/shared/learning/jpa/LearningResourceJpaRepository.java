package com.example.intelligent_cv_matching_ai_agent.repository.shared.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.learning.LearningResource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for learning_resources. */
public interface LearningResourceJpaRepository extends JpaRepository<LearningResource, UUID> {
}
