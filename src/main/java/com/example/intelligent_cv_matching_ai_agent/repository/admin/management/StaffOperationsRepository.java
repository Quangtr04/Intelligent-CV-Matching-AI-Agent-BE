package com.example.intelligent_cv_matching_ai_agent.repository.admin.management;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Operational queries for the Staff dashboard without financial or infrastructure data. */
@Repository
public class StaffOperationsRepository {

    private static final String CONSULTANTS = "SELECT u.id AS user_id,u.full_name,u.email,u.status,"
        + "COALESCE(p.bio,'') AS bio,COALESCE(p.target_roles,'[]'::jsonb) AS target_roles,"
        + "COALESCE(b.total_bookings,0) AS total_bookings,COALESCE(b.completed_bookings,0) AS completed_bookings,"
        + "COALESCE(b.cancelled_bookings,0) AS cancelled_bookings,COALESCE(b.declined_bookings,0) AS declined_bookings,"
        + "COALESCE(b.upcoming_bookings,0) AS upcoming_bookings,r.average_rating,"
        + "COALESCE(r.rating_count,0) AS rating_count "
        + "FROM users u LEFT JOIN consultant_profiles p ON p.user_id=u.id LEFT JOIN ("
        + "SELECT consultant_user_id,count(*) AS total_bookings,"
        + "count(*) FILTER(WHERE status='COMPLETED') AS completed_bookings,"
        + "count(*) FILTER(WHERE status='CANCELLED') AS cancelled_bookings,"
        + "count(*) FILTER(WHERE status='DECLINED') AS declined_bookings,"
        + "count(*) FILTER(WHERE status IN ('PENDING','ACCEPTED') AND starts_at>now()) AS upcoming_bookings "
        + "FROM consultation_bookings GROUP BY consultant_user_id) b ON b.consultant_user_id=u.id LEFT JOIN ("
        + "SELECT consultant_user_id,round(avg(rating),2) AS average_rating,count(*) AS rating_count "
        + "FROM consultation_ratings WHERE NOT is_removed GROUP BY consultant_user_id) r ON r.consultant_user_id=u.id ";

    private final SqlStore db;

    public StaffOperationsRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> dashboard() {
        return db.one("""
            SELECT (SELECT count(*) FROM market_job_posts WHERE status='ACTIVE'
                     AND (valid_through IS NULL OR valid_through>now())) AS active_jobs,
                   (SELECT count(*) FROM market_job_posts WHERE status='PENDING') AS pending_jobs,
                   (SELECT count(*) FROM market_job_posts
                     WHERE status IN ('EXPIRED','INVALID','REJECTED','REMOVED')
                        OR (valid_through IS NOT NULL AND valid_through<=now())) AS expired_or_invalid_jobs,
                   (SELECT count(*) FROM (
                     SELECT content_fingerprint FROM market_job_posts
                     WHERE content_fingerprint IS NOT NULL
                     GROUP BY content_fingerprint HAVING count(*)>1
                   ) d) AS duplicate_job_groups,
                   (SELECT count(*) FROM resume_templates WHERE is_active) AS active_resume_templates,
                   (SELECT count(*) FROM resume_templates WHERE NOT is_active) AS inactive_resume_templates,
                   (SELECT count(*) FROM users WHERE role='CAREER_CONSULTANT' AND status='ACTIVE') AS active_consultants,
                   (SELECT count(*) FROM consultation_ratings WHERE NOT is_removed) AS total_consultant_ratings,
                   (SELECT round(avg(rating),2) FROM consultation_ratings WHERE NOT is_removed) AS average_consultant_rating,
                   (SELECT count(*) FROM consultation_rating_appeals WHERE status='PENDING') AS pending_rating_appeals,
                   (SELECT count(*) FROM consultation_rating_appeals
                     WHERE status='STAFF_APPROVED') AS forwarded_rating_appeals,
                   (SELECT count(*) FROM consultation_rating_appeals
                     WHERE status IN ('STAFF_REJECTED','ACCEPTED','REJECTED')) AS resolved_rating_appeals
            """);
    }

    public List<Map<String, Object>> consultants(
        String search,
        int limit,
        int offset) {
        return db.list(
            CONSULTANTS + "WHERE u.role='CAREER_CONSULTANT' AND u.status<>'DELETED' "
                + "AND (u.email ILIKE ? OR u.full_name ILIKE ?) ORDER BY u.full_name,u.id LIMIT ? OFFSET ?",
            search,
            search,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> consultantRatings(
        UUID consultantId,
        Integer rating,
        Boolean includeRemoved,
        int limit,
        int offset) {
        boolean showRemoved = Boolean.TRUE.equals(includeRemoved);
        return db.list(
            "SELECT r.id,r.booking_id,r.candidate_user_id,r.consultant_user_id,r.rating,r.comment,"
                + "r.is_removed,r.created_at,c.full_name AS candidate_name,u.full_name AS consultant_name,"
                + "b.target_role,b.subject,b.starts_at,b.ends_at,b.duration_minutes,a.id AS appeal_id,"
                + "a.status AS appeal_status,a.staff_decision,a.staff_reviewed_at,a.reviewed_at "
                + "FROM consultation_ratings r " + "JOIN users c ON c.id=r.candidate_user_id "
                + "JOIN users u ON u.id=r.consultant_user_id " + "JOIN consultation_bookings b ON b.id=r.booking_id "
                + "LEFT JOIN consultation_rating_appeals a ON a.rating_id=r.id "
                + "WHERE (?::uuid IS NULL OR r.consultant_user_id=?::uuid) "
                + "AND (?::int IS NULL OR r.rating=?::int) " + "AND (? OR NOT r.is_removed) "
                + "ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",
            consultantId,
            consultantId,
            rating,
            rating,
            showRemoved,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> feedback(
        String source,
        int limit,
        int offset) {
        return db.list(
            "SELECT f.id,f.user_id,u.full_name,u.email,f.source,f.rating,f.feedback_text,f.created_at FROM ("
                + "SELECT id,user_id,'MATCHING' AS source,rating,comment AS feedback_text,created_at "
                + "FROM job_match_feedback "
                + "UNION ALL SELECT id,user_id,'ASSISTANT' AS source,rating,feedback_text,created_at "
                + "FROM career_assistant_feedback UNION ALL SELECT id,candidate_user_id,'CONSULTATION' AS source,"
                + "rating,comment,created_at FROM consultation_ratings WHERE NOT is_removed) f "
                + "LEFT JOIN users u ON u.id=f.user_id " + "WHERE (?::varchar IS NULL OR f.source=?) "
                + "ORDER BY f.created_at DESC,f.id LIMIT ? OFFSET ?",
            source,
            source,
            limit,
            offset
        );
    }

}
