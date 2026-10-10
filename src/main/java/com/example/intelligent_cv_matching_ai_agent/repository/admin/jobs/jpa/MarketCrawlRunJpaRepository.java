package com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.admin.jobs.MarketCrawlRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for market_crawl_runs. */
public interface MarketCrawlRunJpaRepository extends JpaRepository<MarketCrawlRun, UUID> {
}
