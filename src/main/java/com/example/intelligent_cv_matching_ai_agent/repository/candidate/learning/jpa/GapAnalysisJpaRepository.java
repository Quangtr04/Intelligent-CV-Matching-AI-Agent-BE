package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.GapAnalysis;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for gap_analyses. */
public interface GapAnalysisJpaRepository extends JpaRepository<GapAnalysis, UUID> {
}
