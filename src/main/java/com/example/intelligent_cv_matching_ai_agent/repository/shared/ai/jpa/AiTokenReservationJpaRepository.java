package com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.ai.AiTokenReservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for ai_token_reservations. */
public interface AiTokenReservationJpaRepository extends JpaRepository<AiTokenReservation, UUID> {
}
