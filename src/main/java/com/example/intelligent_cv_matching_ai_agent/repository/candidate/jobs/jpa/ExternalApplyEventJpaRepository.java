package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs.ExternalApplyEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for external_apply_events. */
public interface ExternalApplyEventJpaRepository extends JpaRepository<ExternalApplyEvent, UUID> {
}
