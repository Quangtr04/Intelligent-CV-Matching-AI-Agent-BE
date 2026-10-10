package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.JobMatchResult;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for job_match_results. */
public interface JobMatchResultJpaRepository extends JpaRepository<JobMatchResult, UUID> {
}
