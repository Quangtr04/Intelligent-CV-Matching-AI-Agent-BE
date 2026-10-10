package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning.GapAnalysisSkill;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for gap_analysis_skills. */
public interface GapAnalysisSkillJpaRepository extends JpaRepository<GapAnalysisSkill, UUID> {
}
