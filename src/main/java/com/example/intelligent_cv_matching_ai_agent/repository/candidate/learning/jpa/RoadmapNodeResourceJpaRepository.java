package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.RoadmapNodeResource;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.RoadmapNodeResourceId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for roadmap_node_resources. */
public interface RoadmapNodeResourceJpaRepository extends JpaRepository<RoadmapNodeResource, RoadmapNodeResourceId> {
}
