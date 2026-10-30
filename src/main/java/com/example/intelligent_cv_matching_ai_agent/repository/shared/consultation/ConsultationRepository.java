package com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by ConsultationService. */
@Repository
public class ConsultationRepository {

    private final SqlStore db;

    public ConsultationRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getConsultationThreadsById(
        Object... args) {
        return db.one("SELECT * FROM consultation_threads WHERE id=?", args);
    }

    public UUID insertConsultationThreads(
        Map<String, Object> fields) {
        return db.insert("consultation_threads", fields);
    }

    public List<Map<String, Object>> listConsultationThreads(
        Object... args) {
        return db.list("SELECT * FROM consultation_threads ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listConsultationThreadsByCandidateUserId(
        Object... args) {
        return db.list(
            ("SELECT * FROM consultation_threads WHERE candidate_user_id=? OR "
                + "consultant_user_id=? ORDER BY created_at DESC LIMIT 100"),
            args
        );
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public List<Map<String, Object>> listCandidateProfileSkillsByUserId(
        Object... args) {
        return db.list(
            ("SELECT s.name,ps.proficiency_level,ps.years_experience FROM "
                + "candidate_profile_skills ps JOIN skills s ON s.id=ps.skill_id WHERE "
                + "ps.user_id=? ORDER BY s.name"),
            args
        );
    }

    public Map<String, Object> getUsersById(
        Object... args) {
        return db.one("SELECT id,full_name,preferred_language FROM users WHERE id=? AND status<>'DELETED'", args);
    }

    public Optional<Map<String, Object>> findResumesByUserId(
        Object... args) {
        return db.optional(
            ("SELECT id,title,language,structured_data,updated_at FROM resumes "
                + "WHERE user_id=? AND is_primary AND status='READY'"),
            args
        );
    }

    public Map<String, Object> getResumesById(
        Object... args) {
        return db.one(
            ("SELECT id,title,language,structured_data,updated_at FROM resumes "
                + "WHERE id=? AND user_id=? AND status='READY'"),
            args
        );
    }

    public Map<String, Object> getConsultationThreadsByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM consultation_threads WHERE id=? AND status='OPEN' FOR UPDATE", args);
    }

    public Map<String, Object> getUsersById2(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? AND role='CAREER_CONSULTANT' AND status='ACTIVE'", args);
    }

    public int updateConsultationThreadsById(
        Object... args) {
        return db.update("UPDATE consultation_threads SET consultant_user_id=? WHERE id=?", args);
    }

    public UUID insertAuditLogs(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }

    public List<Map<String, Object>> listConsultationMessagesByThreadId(
        Object... args) {
        return db.list(
            ("SELECT m.*,u.full_name,u.role FROM consultation_messages m JOIN users "
                + "u ON u.id=m.sender_user_id WHERE thread_id=? ORDER BY sent_at,id " + "LIMIT ? OFFSET ?"),
            args
        );
    }

    public Map<String, Object> getConsultationThreadsByIdForUpdate2(
        Object... args) {
        return db.one("SELECT id FROM consultation_threads WHERE id=? FOR UPDATE", args);
    }

    public UUID insertConsultationMessages(
        Map<String, Object> fields) {
        return db.insert("consultation_messages", fields);
    }

    public Map<String, Object> getConsultationMessagesById(
        Object... args) {
        return db.one("SELECT * FROM consultation_messages WHERE id=?", args);
    }

    public Map<String, Object> getConsultationMessagesById2(
        Object... args) {
        return db.one("SELECT id FROM consultation_messages WHERE id=? AND thread_id=?", args);
    }

    public int updateConsultationMessagesById(
        Object... args) {
        return db.update(
            ("UPDATE consultation_messages SET read_at=COALESCE(read_at,now()) " + "WHERE id=? AND sender_user_id<>?"),
            args
        );
    }

    public int updateConsultationThreadsById2(
        Object... args) {
        return db.update(
            ("UPDATE consultation_threads SET status='CLOSED'," + "closed_at=COALESCE(closed_at,now()) WHERE id=?"),
            args
        );
    }

    public Optional<Map<String, Object>> findConsultationThreadsById(
        Object... args) {
        return db.optional(
            ("SELECT 1 FROM consultation_threads t JOIN users u ON u.id=? WHERE "
                + "t.id=? AND u.status='ACTIVE' AND (u.role='ADMIN' OR "
                + "u.id=t.candidate_user_id OR u.id=t.consultant_user_id)"),
            args
        );
    }

    public List<Map<String, Object>> listActiveAdmins(
        Object... args) {
        return db.list("SELECT id FROM users WHERE role='ADMIN' AND status='ACTIVE'", args);
    }

    public Optional<Map<String, Object>> findUnreadNotificationsByUserIdAndReference(
        Object... args) {
        return db.optional(
            ("SELECT 1 FROM notifications WHERE user_id=? AND type=? AND reference_type='CONSULTATION' "
                + "AND reference_id=? AND NOT is_read LIMIT 1"),
            args
        );
    }

    public UUID insertNotifications(
        Map<String, Object> fields) {
        return db.insert("notifications", fields);
    }

}
