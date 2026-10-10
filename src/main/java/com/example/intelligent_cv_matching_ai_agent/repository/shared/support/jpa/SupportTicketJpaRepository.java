package com.example.intelligent_cv_matching_ai_agent.repository.shared.support.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.support.SupportTicket;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for support_tickets. */
public interface SupportTicketJpaRepository extends JpaRepository<SupportTicket, UUID> {
}
