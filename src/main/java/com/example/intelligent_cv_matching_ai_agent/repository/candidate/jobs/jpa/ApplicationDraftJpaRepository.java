package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.ApplicationDraft;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for application_drafts. */
public interface ApplicationDraftJpaRepository extends JpaRepository<ApplicationDraft, UUID> {
}
