package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.JobMatchingRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for job_matching_runs. */
public interface JobMatchingRunJpaRepository extends JpaRepository<JobMatchingRun, UUID> {
}
