package com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Repository
public class ConsultationAdviceRepository {

    private final SqlStore db;

    public ConsultationAdviceRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> context(
        UUID threadId,
        UUID viewerId,
        boolean writing) {
        String lock = writing ? " FOR NO KEY UPDATE OF t NOWAIT" : " FOR SHARE OF t";
        try {
            return db.one("""
                SELECT t.id,t.candidate_user_id,t.consultant_user_id,t.request_type,t.status,t.resume_id,t.job_id,
                       b.status AS booking_status,b.credit_refunded
                FROM consultation_threads t
                JOIN users viewer ON viewer.id=? AND viewer.status='ACTIVE'
                JOIN users candidate ON candidate.id=t.candidate_user_id AND candidate.status='ACTIVE'
                LEFT JOIN consultation_bookings b ON b.thread_id=t.id
                WHERE t.id=? AND (
                    (viewer.role='CANDIDATE' AND viewer.id=t.candidate_user_id)
                    OR (viewer.role='CAREER_CONSULTANT' AND viewer.id=t.consultant_user_id)
                )
                """ + lock, viewerId, threadId);
        } catch (DataAccessException failure) {
            if (failure instanceof org.springframework.dao.CannotAcquireLockException
                || failure.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && "55P03".equals(sql.getSQLState())) {
                throw ApiException.conflict("Consultation is being updated; retry shortly");
            }
            throw failure;
        }
    }

    public Optional<Map<String, Object>> advice(
        UUID threadId) {
        return db.optional("""
            SELECT report.*,author.full_name AS consultant_name
            FROM consultation_advice_reports report
            JOIN users author ON author.id=report.consultant_user_id
            WHERE report.thread_id=?
            """, threadId);
    }

    public Map<String, Object> saveAdvice(
        UUID threadId,
        UUID candidateId,
        UUID consultantId,
        UUID resumeId,
        UUID jobId,
        String status,
        Map<String, Object> advice,
        long expectedVersion) {
        return db.optional("""
            INSERT INTO consultation_advice_reports
                (thread_id,candidate_user_id,consultant_user_id,resume_id,market_job_id,status,advice_json,published_at)
            VALUES (?,?,?,?,?,?,?,CASE WHEN ?='PUBLISHED' THEN now() ELSE NULL END)
            ON CONFLICT(thread_id) DO UPDATE SET
                consultant_user_id=EXCLUDED.consultant_user_id,resume_id=EXCLUDED.resume_id,
                market_job_id=EXCLUDED.market_job_id,status=EXCLUDED.status,advice_json=EXCLUDED.advice_json,
                version=consultation_advice_reports.version+1,updated_at=now(),
                published_at=CASE WHEN EXCLUDED.status='PUBLISHED'
                    THEN COALESCE(consultation_advice_reports.published_at,now()) ELSE NULL END
            WHERE consultation_advice_reports.version=?
              AND consultation_advice_reports.candidate_user_id=EXCLUDED.candidate_user_id
            RETURNING *
            """, threadId, candidateId, consultantId, resumeId, jobId, status, db.json(advice), status, expectedVersion)
            .orElseThrow(() -> ApiException.conflict("Advice changed; reload the report before saving"));
    }

    public Optional<Map<String, Object>> privateNotes(
        UUID threadId,
        UUID consultantId) {
        return db.optional(
            "SELECT * FROM consultation_private_notes WHERE thread_id=? AND consultant_user_id=?",
            threadId,
            consultantId
        );
    }

    public Map<String, Object> savePrivateNotes(
        UUID threadId,
        UUID consultantId,
        String notes,
        Map<String, Object> rubric,
        long expectedVersion) {
        return db.optional("""
            INSERT INTO consultation_private_notes(thread_id,consultant_user_id,notes,rubric_json)
            VALUES (?,?,?,?)
            ON CONFLICT(thread_id,consultant_user_id) DO UPDATE SET
                notes=EXCLUDED.notes,rubric_json=EXCLUDED.rubric_json,
                version=consultation_private_notes.version+1,updated_at=now()
            WHERE consultation_private_notes.version=?
            RETURNING *
            """, threadId, consultantId, notes, db.json(rubric), expectedVersion)
            .orElseThrow(() -> ApiException.conflict("Private notes changed; reload them before saving"));
    }

    public void publishedNotification(
        UUID threadId,
        UUID candidateId) {
        db.insert(
            "notifications",
            fields(
                "user_id",
                candidateId,
                "type",
                "CONSULTATION_ADVICE_PUBLISHED",
                "title",
                "Báo cáo tư vấn của bạn đã được cập nhật",
                "content",
                "Cố vấn đã gửi lời khuyên về CV, định hướng nghề nghiệp và chuẩn bị ứng tuyển.",
                "reference_type",
                "CONSULTATION",
                "reference_id",
                threadId
            )
        );
    }

    public void audit(
        UUID threadId,
        UUID actor,
        String action,
        long version) {
        db.insert(
            "audit_logs",
            fields(
                "actor_user_id",
                actor,
                "action",
                action,
                "entity_type",
                "consultation_threads",
                "entity_id",
                threadId,
                "new_value",
                db.json(fields("version", version))
            )
        );
    }
}
