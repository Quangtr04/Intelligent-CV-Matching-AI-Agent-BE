package com.example.intelligent_cv_matching_ai_agent.repository.shared.support.jpa;

import com.example.intelligent_cv_matching_ai_agent.entity.shared.support.SupportTicketMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Typed persistence access for support_ticket_messages. */
public interface SupportTicketMessageJpaRepository extends JpaRepository<SupportTicketMessage, UUID> {
}
