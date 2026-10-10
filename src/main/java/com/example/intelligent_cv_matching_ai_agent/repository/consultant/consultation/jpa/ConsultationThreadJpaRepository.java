package com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.consultant.consultation.ConsultationThread;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for consultation_threads. */
public interface ConsultationThreadJpaRepository extends JpaRepository<ConsultationThread, UUID> {
}
