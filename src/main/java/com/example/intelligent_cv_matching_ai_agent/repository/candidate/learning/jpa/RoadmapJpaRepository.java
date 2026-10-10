package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.Roadmap;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for roadmaps. */
public interface RoadmapJpaRepository extends JpaRepository<Roadmap, UUID> {
}
