package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.jobs.MarketJobPost;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for market_job_posts. */
public interface MarketJobPostJpaRepository extends JpaRepository<MarketJobPost, UUID> {
}
