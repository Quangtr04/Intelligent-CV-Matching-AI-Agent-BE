package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.MatchingRunResume;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.MatchingRunResumeId;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for matching_run_resumes. */
public interface MatchingRunResumeJpaRepository extends JpaRepository<MatchingRunResume, MatchingRunResumeId> {
}
