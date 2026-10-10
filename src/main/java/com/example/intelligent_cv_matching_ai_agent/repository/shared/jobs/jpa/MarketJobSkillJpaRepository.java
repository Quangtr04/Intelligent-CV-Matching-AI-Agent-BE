package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.jobs.MarketJobSkill;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for market_job_skills. */
public interface MarketJobSkillJpaRepository extends JpaRepository<MarketJobSkill, UUID> {
}
