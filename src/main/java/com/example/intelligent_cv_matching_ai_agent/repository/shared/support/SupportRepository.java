package com.example.intelligent_cv_matching_ai_agent.repository.shared.support;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by SupportService. */
@Repository
public class SupportRepository {

    private final SqlStore db;

    public SupportRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getTicket(
        UUID id) {
        return db.one("SELECT * FROM support_tickets WHERE id=?", id);
    }

    public Map<String, Object> getTicketForUpdate(
        UUID id) {
        return db.one("SELECT * FROM support_tickets WHERE id=? FOR UPDATE", id);
    }

    public List<Map<String, Object>> listSupportTickets(
        Object... args) {
        return db.list("SELECT * FROM support_tickets ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listSupportTicketsByUserId(
        Object... args) {
        return db.list("SELECT * FROM support_tickets WHERE user_id=? ORDER BY created_at DESC LIMIT 100", args);
    }

    public UUID insertSupportTickets(
        Map<String, Object> fields) {
        return db.insert("support_tickets", fields);
    }

    public List<Map<String, Object>> listSupportTicketMessagesByTicketId(
        Object... args) {
        return db.list(
            ("SELECT m.*,u.full_name FROM support_ticket_messages m JOIN users u ON "
                + "u.id=m.sender_user_id WHERE m.ticket_id=? ORDER BY m.created_at LIMIT " + "500"),
            args
        );
    }

    public UUID insertSupportTicketMessages(
        Map<String, Object> fields) {
        return db.insert("support_ticket_messages", fields);
    }

    public int updateSupportTicketsById(
        Object... args) {
        return db.update("UPDATE support_tickets SET updated_at=now() WHERE id=?", args);
    }

    public UUID insertNotifications(
        Map<String, Object> fields) {
        return db.insert("notifications", fields);
    }

    public Map<String, Object> getSupportTicketMessagesById(
        Object... args) {
        return db.one("SELECT * FROM support_ticket_messages WHERE id=?", args);
    }

    public Map<String, Object> getUsersById(
        Object... args) {
        return db.one(
            "SELECT id FROM users WHERE id=? AND role IN ('ADMIN','MANAGEMENT','MANAGER') AND status='ACTIVE'",
            args
        );
    }

    public int updateSupportTicketsById2(
        Object... args) {
        return db.update(
            ("UPDATE support_tickets SET status=?,priority=?,assigned_admin_id=?,"
                + "resolved_at=CASE WHEN ? IN ('RESOLVED','CLOSED') THEN now() ELSE NULL "
                + "END,updated_at=now() WHERE id=?"),
            args
        );
    }

}
