package com.example.intelligent_cv_matching_ai_agent.service.shared.support;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.support.SupportTicketUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.support.SupportTicketRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.support.SupportMessageRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.support.SupportRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SupportService {

    private final SupportRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    public SupportService(
        SupportRepository db,
        CurrentUser user,
        AuditService audit) {
        this.db = db;
        this.user = user;
        this.audit = audit;
    }

    public List<Map<String, Object>> list() {
        return user.hasRole("ADMIN") ? db.listSupportTickets() : db.listSupportTicketsByUserId(user.id());
    }

    public Map<String, Object> create(
        SupportTicketRequest body) {
        OffsetDateTime now = OffsetDateTime.now();

        UUID id = db.insertSupportTickets(
            Map.of(
                "user_id",
                user.id(),
                "subject",
                body.subject(),
                "category",
                body.category(),
                "description",
                body.description(),
                "status",
                "OPEN",
                "priority",
                "NORMAL",
                "created_at",
                now,
                "updated_at",
                now
            )
        );

        return detail(id);
    }

    private Map<String, Object> owned(
        UUID id) {
        return owned(id, false);
    }

    private Map<String, Object> owned(
        UUID id,
        boolean lock) {
        Map<String, Object> ticket = lock ? db.getTicketForUpdate(id) : db.getTicket(id);

        if (!user.hasRole("ADMIN"))
            user.owns(ticket, "user_id");

        return ticket;
    }

    public Map<String, Object> detail(
        UUID id) {
        Map<String, Object> result = new LinkedHashMap<>(owned(id));
        result.put("messages", db.listSupportTicketMessagesByTicketId(id));

        return result;
    }

    @Transactional
    public Map<String, Object> message(
        UUID id,
        SupportMessageRequest body) {
        Map<String, Object> ticket = owned(id, true);
        if ("CLOSED".equals(ticket.get("status")))
            throw ApiException.conflict("Ticket is closed");

        UUID message = db.insertSupportTicketMessages(
            Map.of(
                "ticket_id",
                id,
                "sender_user_id",
                user.id(),
                "content",
                body.content(),
                "created_at",
                OffsetDateTime.now()
            )
        );
        db.updateSupportTicketsById(id);

        if (user.hasRole("ADMIN") && !user.id().equals(ticket.get("user_id")))
            db.insertNotifications(
                Map.of(
                    "user_id",
                    ticket.get("user_id"),
                    "type",
                    "SUPPORT_REPLY",
                    "title",
                    "Support replied",
                    "content",
                    body.content(),
                    "reference_type",
                    "SUPPORT_TICKET",
                    "reference_id",
                    id,
                    "created_at",
                    OffsetDateTime.now()
                )
            );

        return db.getSupportTicketMessagesById(message);
    }

    @Transactional
    public Map<String, Object> update(
        UUID id,
        SupportTicketUpdateRequest body) {
        user.requireRole("ADMIN");

        Map<String, Object> before = owned(id, true);

        if (body.assignedAdminId() != null)
            db.getUsersById(body.assignedAdminId());

        db.updateSupportTicketsById2(body.status(), body.priority(), body.assignedAdminId(), body.status(), id);

        Map<String, Object> after = owned(id);

        audit.record("UPDATE_SUPPORT_TICKET", "SUPPORT_TICKET", id, before, after);

        return detail(id);
    }
}
