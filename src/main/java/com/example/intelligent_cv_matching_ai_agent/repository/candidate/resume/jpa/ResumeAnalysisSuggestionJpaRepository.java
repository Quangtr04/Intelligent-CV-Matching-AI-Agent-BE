package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.ResumeAnalysisSuggestion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for resume_analysis_suggestions. */
public interface ResumeAnalysisSuggestionJpaRepository extends JpaRepository<ResumeAnalysisSuggestion, UUID> {
}
