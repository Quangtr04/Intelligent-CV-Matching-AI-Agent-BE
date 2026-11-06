package com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Repository
public class ConsultationBookingRepository {

    private final SqlStore db;

    private static final String ENRICHED = "SELECT b.*,c.full_name AS candidate_name,"
        + "u.full_name AS consultant_name,r.rating,r.comment AS rating_comment,r.created_at AS rating_created_at "
        + "FROM consultation_bookings b JOIN users c ON c.id=b.candidate_user_id "
        + "JOIN users u ON u.id=b.consultant_user_id LEFT JOIN consultation_ratings r ON r.booking_id=b.id ";

    public ConsultationBookingRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> consultants() {
        return db.list(
            "SELECT u.id,u.full_name,p.bio,p.target_roles,COALESCE(r.average_rating,0) AS average_rating,"
                + "COALESCE(r.rating_count,0) AS rating_count,COALESCE(b.completed_count,0) AS completed_count "
                + "FROM users u JOIN consultant_profiles p ON p.user_id=u.id "
                + "LEFT JOIN (SELECT consultant_user_id,round(avg(rating),2) AS average_rating,"
                + "count(*) AS rating_count FROM consultation_ratings GROUP BY consultant_user_id) r "
                + "ON r.consultant_user_id=u.id LEFT JOIN (SELECT consultant_user_id,count(*) AS completed_count "
                + "FROM consultation_bookings WHERE status='COMPLETED' GROUP BY consultant_user_id) b "
                + "ON b.consultant_user_id=u.id WHERE u.role='CAREER_CONSULTANT' AND u.status='ACTIVE' "
                + "ORDER BY average_rating DESC,rating_count DESC,completed_count DESC,u.full_name,u.id"
        );
    }

    public Map<String, Object> consultant(
        UUID id) {
        return db.one(
            "SELECT u.id,u.full_name,COALESCE(p.bio,'') AS bio,COALESCE(p.target_roles,'[]'::jsonb) AS target_roles "
                + "FROM users u LEFT JOIN consultant_profiles p ON p.user_id=u.id "
                + "WHERE u.id=? AND u.role='CAREER_CONSULTANT' AND u.status='ACTIVE'",
            id
        );
    }

    public void lockPeople(
        UUID candidate,
        UUID consultant) {
        // Every booking, busy-slot and profile mutation takes these user locks first.
        // Stable UUID order also serializes a candidate booking two different consultants.
        List<Map<String, Object>> people = db.list(
            "SELECT id,role,status FROM users WHERE id IN (?,?) ORDER BY id FOR NO KEY UPDATE",
            candidate,
            consultant
        );
        boolean candidateExists =
            people.stream().anyMatch(p -> candidate.equals(p.get("id")) && "CANDIDATE".equals(p.get("role")));
        boolean consultantExists =
            people.stream().anyMatch(p -> consultant.equals(p.get("id")) && "CAREER_CONSULTANT".equals(p.get("role")));
        if (!candidateExists || !consultantExists) {
            throw ApiException.notFound("Candidate and consultant are required");
        }
    }

    public void lockConsultant(
        UUID id) {
        db.one(
            "SELECT id FROM users WHERE id=? AND role='CAREER_CONSULTANT' AND status='ACTIVE' FOR NO KEY UPDATE",
            id
        );
    }

    public Map<String, Object> profile(
        UUID id) {
        Map<String, Object> row = consultant(id);
        return fields("user_id", id, "bio", row.get("bio"), "target_roles", row.get("target_roles"));
    }

    public Map<String, Object> saveProfile(
        UUID id,
        List<String> roles,
        String bio) {
        db.update(
            "INSERT INTO consultant_profiles(user_id,target_roles,bio) VALUES (?,?,?) "
                + "ON CONFLICT(user_id) DO UPDATE SET target_roles=EXCLUDED.target_roles,bio=EXCLUDED.bio",
            id,
            db.json(roles),
            bio
        );
        return profile(id);
    }

    public List<Map<String, Object>> availability(
        UUID consultant,
        OffsetDateTime from,
        OffsetDateTime to) {
        return db.list(
            "SELECT id,starts_at,ends_at,'BOOKING' AS type FROM consultation_bookings "
                + "WHERE consultant_user_id=? AND status IN ('PENDING','ACCEPTED') AND starts_at<? AND ends_at>? "
                + "UNION ALL SELECT id,starts_at,ends_at,'BUSY' AS type FROM consultant_busy_slots "
                + "WHERE user_id=? AND starts_at<? AND ends_at>? ORDER BY starts_at,id",
            consultant,
            to,
            from,
            consultant,
            to,
            from
        );
    }

    public List<Map<String, Object>> busy(
        UUID consultant) {
        return db.list(
            "SELECT * FROM consultant_busy_slots WHERE user_id=? AND ends_at>now() ORDER BY starts_at LIMIT 500",
            consultant
        );
    }

    public boolean overlaps(
        UUID candidate,
        UUID consultant,
        OffsetDateTime start,
        OffsetDateTime end) {
        return !db.optional(
            "SELECT id FROM consultation_bookings WHERE (candidate_user_id=? OR consultant_user_id=?) "
                + "AND status IN ('PENDING','ACCEPTED') AND starts_at<? AND ends_at>? LIMIT 1",
            candidate,
            consultant,
            end,
            start
        ).isEmpty()
            || !db.optional(
                "SELECT id FROM consultant_busy_slots WHERE user_id=? AND starts_at<? AND ends_at>? LIMIT 1",
                consultant,
                end,
                start
            ).isEmpty();
    }

    public boolean busyOverlapsBooking(
        UUID consultant,
        OffsetDateTime start,
        OffsetDateTime end) {
        return !db.optional(
            "SELECT id FROM consultation_bookings WHERE consultant_user_id=? "
                + "AND status IN ('PENDING','ACCEPTED') AND starts_at<? AND ends_at>? LIMIT 1",
            consultant,
            end,
            start
        ).isEmpty();
    }

    public Map<String, Object> addBusy(
        UUID consultant,
        OffsetDateTime start,
        OffsetDateTime end,
        String description) {
        UUID id = db.insert(
            "consultant_busy_slots",
            fields("user_id", consultant, "starts_at", start, "ends_at", end, "description", description)
        );
        return db.one("SELECT * FROM consultant_busy_slots WHERE id=?", id);
    }

    public void removeBusy(
        UUID id,
        UUID consultant) {
        db.one("SELECT id FROM consultant_busy_slots WHERE id=? AND user_id=?", id, consultant);
        db.update("DELETE FROM consultant_busy_slots WHERE id=? AND user_id=?", id, consultant);
    }

    public Map<String, Object> credit(
        UUID id,
        UUID candidate) {
        return db.one("SELECT * FROM consultation_orders WHERE id=? AND candidate_user_id=? FOR UPDATE", id, candidate);
    }

    public void reserveCredit(
        UUID id) {
        if (db.update(
            "UPDATE consultation_orders SET sessions_remaining=sessions_remaining-1 "
                + "WHERE id=? AND status='PAID' AND is_session_pack AND sessions_remaining>0",
            id
        ) != 1) {
            throw ApiException.conflict("No paid consultation sessions remain");
        }
    }

    public void refundCredit(
        UUID id) {
        db.one("SELECT id FROM consultation_orders WHERE id=? FOR UPDATE", id);
        db.update(
            "UPDATE consultation_orders SET sessions_remaining=LEAST(sessions_total,sessions_remaining+1) WHERE id=?",
            id
        );
    }

    public UUID createThread(
        Map<String, Object> values) {
        return db.insert("consultation_threads", values);
    }

    public UUID insert(
        Map<String, Object> values) {
        return db.insert("consultation_bookings", values);
    }

    public Map<String, Object> booking(
        UUID id) {
        return db.one(ENRICHED + "WHERE b.id=?", id);
    }

    public Map<String, Object> lockBooking(
        UUID id) {
        return db.one("SELECT * FROM consultation_bookings WHERE id=? FOR UPDATE", id);
    }

    public List<Map<String, Object>> bookings(
        UUID viewer,
        boolean admin) {
        return db.list(
            ENRICHED + (admin ? "" : "WHERE b.candidate_user_id=? OR b.consultant_user_id=? ")
                + "ORDER BY b.starts_at DESC,b.id DESC LIMIT 500",
            admin ? new Object[]{} : new Object[]{viewer, viewer}
        );
    }

    public void accept(
        UUID id) {
        db.update("UPDATE consultation_bookings SET status='ACCEPTED',accepted_at=now() WHERE id=?", id);
    }

    public void end(
        UUID id,
        String status,
        boolean refund) {
        db.update(
            "UPDATE consultation_bookings SET status=?,credit_refunded=?,"
                + "completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE completed_at END WHERE id=?",
            status,
            refund,
            status,
            id
        );
    }

    public void closeThread(
        UUID thread) {
        // AI evaluation holds a thread share lock while a REQUIRES_NEW quota transaction
        // may need a user lock. Never wait for that thread while holding booking user locks.
        try {
            db.one("SELECT id FROM consultation_threads WHERE id=? FOR NO KEY UPDATE NOWAIT", thread);
        } catch (org.springframework.dao.DataAccessException ex) {
            if (ex instanceof org.springframework.dao.CannotAcquireLockException
                || (ex.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && "55P03".equals(sql.getSQLState()))) {
                throw ApiException.conflict("Consultation is being updated; please retry shortly");
            }
            throw ex;
        }
        db.update(
            "UPDATE consultation_threads SET status='CLOSED',closed_at=COALESCE(closed_at,now()) WHERE id=?",
            thread
        );
    }

    public void rate(
        Map<String, Object> values) {
        db.insert("consultation_ratings", values);
    }

    public void inform(
        UUID recipient,
        UUID thread,
        String type,
        String title,
        String message) {
        db.insert(
            "notifications",
            fields(
                "user_id",
                recipient,
                "type",
                type,
                "title",
                title,
                "content",
                message,
                "reference_type",
                "CONSULTATION",
                "reference_id",
                thread,
                "created_at",
                OffsetDateTime.now()
            )
        );
    }

    public List<Map<String, Object>> due() {
        return db.list(
            "SELECT id FROM consultation_bookings WHERE (status='ACCEPTED' AND ends_at<=now()) "
                + "OR (status='PENDING' AND starts_at<=now()) ORDER BY starts_at LIMIT 100"
        );
    }
}
