package com.example.intelligent_cv_matching_ai_agent.repository.guest.trial.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.guest.trial.GuestSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for guest_sessions. */
public interface GuestSessionJpaRepository extends JpaRepository<GuestSession, UUID> {
}
