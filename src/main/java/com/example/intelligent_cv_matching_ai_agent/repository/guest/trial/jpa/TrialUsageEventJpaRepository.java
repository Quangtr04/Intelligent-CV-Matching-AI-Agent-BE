package com.example.intelligent_cv_matching_ai_agent.repository.guest.trial.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.guest.trial.TrialUsageEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for trial_usage_events. */
public interface TrialUsageEventJpaRepository extends JpaRepository<TrialUsageEvent, UUID> {
}
