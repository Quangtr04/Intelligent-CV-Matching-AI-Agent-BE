package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.JobResumeAnalysis;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for job_resume_analyses. */
public interface JobResumeAnalysisJpaRepository extends JpaRepository<JobResumeAnalysis, UUID> {
}
