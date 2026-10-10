package com.example.intelligent_cv_matching_ai_agent.repository.candidate.profile.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.profile.CareerProfileEmbedding;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for career_profile_embeddings. */
public interface CareerProfileEmbeddingJpaRepository extends JpaRepository<CareerProfileEmbedding, UUID> {
}
