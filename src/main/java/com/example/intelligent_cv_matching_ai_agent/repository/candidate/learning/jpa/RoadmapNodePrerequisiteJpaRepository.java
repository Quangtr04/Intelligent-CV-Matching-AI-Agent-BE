package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.RoadmapNodePrerequisite;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.RoadmapNodePrerequisiteId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for roadmap_node_prerequisites. */
public interface RoadmapNodePrerequisiteJpaRepository
    extends
    JpaRepository<RoadmapNodePrerequisite, RoadmapNodePrerequisiteId> {
}
