package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.ResumeAnalysis;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for resume_analyses. */
public interface ResumeAnalysisJpaRepository extends JpaRepository<ResumeAnalysis, UUID> {
}
