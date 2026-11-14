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

    private static final String ENRICHED = ("SELECT b.id,b.candidate_user_id,b.consultant_user_id,"
        + "b.credit_order_id,b.target_role,b.subject,b.description,b.starts_at,"
        + "b.ends_at,b.duration_minutes,b.status,b.thread_id,b.credit_refunded,"
        + "b.created_at,b.accepted_at,b.completed_at,b.meeting_status,"
        + "b.meeting_url,b.calendar_event_url,b.meeting_error,b.resume_id,b.job_id,"
        + "cv.title AS resume_title,j.job_title,j.company_name,"
        + "b.meeting_opened_at,b.meeting_ended_at,c.full_name AS candidate_name,"
        + "u.full_name AS consultant_name,r.rating,r.comment AS rating_comment,"
        + "r.created_at AS rating_created_at FROM consultation_bookings b JOIN "
        + "users c ON c.id=b.candidate_user_id JOIN users u ON "
        + "u.id=b.consultant_user_id LEFT JOIN consultation_ratings r ON r.booking_id=b.id AND NOT r.is_removed "
        + "LEFT JOIN resumes cv ON cv.id=b.resume_id AND cv.user_id=b.candidate_user_id AND cv.status='READY' "
        + "LEFT JOIN market_job_posts j ON j.id=b.job_id ");

    public ConsultationBookingRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> consultants() {
        return db.list(
            ("SELECT u.id,u.full_name,p.bio,p.target_roles," + "COALESCE(r.average_rating,0) AS average_rating,"
                + "COALESCE(r.rating_count,0) AS rating_count,COALESCE(b.completed_count,"
                + "0) AS completed_count FROM users u JOIN consultant_profiles p ON "
                + "p.user_id=u.id LEFT JOIN (SELECT consultant_user_id,round(avg(rating),"
                + "2) AS average_rating,count(*) AS rating_count FROM "
                + "consultation_ratings WHERE NOT is_removed GROUP BY consultant_user_id) r ON "
                + "r.consultant_user_id=u.id LEFT JOIN (SELECT consultant_user_id,"
                + "count(*) AS completed_count FROM consultation_bookings WHERE "
                + "status='COMPLETED' GROUP BY consultant_user_id) b ON "
                + "b.consultant_user_id=u.id WHERE u.role='CAREER_CONSULTANT' AND "
                + "u.status='ACTIVE' ORDER BY average_rating DESC,rating_count DESC,"
                + "completed_count DESC,u.full_name,u.id")
        );
    }

    public void requireReadyResume(
        UUID resumeId,
        UUID candidateId) {
        db.one("SELECT id FROM resumes WHERE id=? AND user_id=? AND status='READY' FOR SHARE", resumeId, candidateId);
    }

    public void requireActiveJob(
        UUID jobId) {
        db.one("""
            SELECT j.id FROM market_job_posts j
            WHERE j.id=? AND j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now())
              AND (NOT EXISTS (SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id)
                   OR EXISTS (SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE'
                              AND (s.valid_through IS NULL OR s.valid_through>now())))
            FOR SHARE OF j
            """, jobId);
    }

    public Optional<Map<String, Object>> selectedResume(
        UUID resumeId,
        UUID candidateId) {
        return db.optional("""
            SELECT id,user_id,title,language,status,source,structured_data,updated_at
            FROM resumes WHERE id=? AND user_id=? AND status='READY'
            """, resumeId, candidateId);
    }

    public void requireLiveCandidate(
        UUID candidateId) {
        db.one("SELECT id FROM users WHERE id=? AND status<>'DELETED'", candidateId);
    }

    public Optional<Map<String, Object>> selectedJob(
        UUID jobId) {
        return db.optional("""
            SELECT id,job_title,company_name,location,salary_text,role_category,raw_content,
                   parsed_description,parsed_requirements,parsed_responsibilities,parsed_benefits,
                   parsed_company_overview,parsed_seniority,employment_type,status,valid_through
            FROM market_job_posts WHERE id=?
            """, jobId);
    }

    public Map<String, Object> consultant(
        UUID id) {
        return db.one(
            ("SELECT u.id,u.full_name,COALESCE(p.bio,'') AS bio,"
                + "COALESCE(p.target_roles,'[]'::jsonb) AS target_roles FROM users u "
                + "LEFT JOIN consultant_profiles p ON p.user_id=u.id WHERE u.id=? AND "
                + "u.role='CAREER_CONSULTANT' AND u.status='ACTIVE'"),
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
            ("SELECT id FROM users WHERE id=? AND role='CAREER_CONSULTANT' AND " + "status='ACTIVE' FOR NO KEY UPDATE"),
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
            ("INSERT INTO consultant_profiles(user_id,target_roles,bio) VALUES (?,?,"
                + "?) ON CONFLICT(user_id) DO UPDATE SET " + "target_roles=EXCLUDED.target_roles,bio=EXCLUDED.bio"),
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
            ("SELECT id,starts_at,ends_at,'BOOKING' AS type FROM "
                + "consultation_bookings WHERE consultant_user_id=? AND status IN "
                + "('PENDING','ACCEPTED') AND starts_at<? AND ends_at>? UNION ALL SELECT "
                + "id,starts_at,ends_at,'BUSY' AS type FROM consultant_busy_slots WHERE "
                + "user_id=? AND starts_at<? AND ends_at>? ORDER BY starts_at,id"),
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
            ("SELECT * FROM consultant_busy_slots WHERE user_id=? AND ends_at>now() " + "ORDER BY starts_at LIMIT 500"),
            consultant
        );
    }

    public boolean overlaps(
        UUID candidate,
        UUID consultant,
        OffsetDateTime start,
        OffsetDateTime end) {
        return !db.optional(
            ("SELECT id FROM consultation_bookings WHERE (candidate_user_id=? OR "
                + "consultant_user_id=?) AND status IN ('PENDING','ACCEPTED') AND "
                + "starts_at<? AND ends_at>? LIMIT 1"),
            candidate,
            consultant,
            end,
            start
        ).isEmpty()
            || !db.optional(
                ("SELECT id FROM consultant_busy_slots WHERE user_id=? AND starts_at<? " + "AND ends_at>? LIMIT 1"),
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
            ("SELECT id FROM consultation_bookings WHERE consultant_user_id=? AND "
                + "status IN ('PENDING','ACCEPTED') AND starts_at<? AND ends_at>? LIMIT 1"),
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

    public List<Map<String, Object>> candidateIntervals(
        UUID candidate,
        OffsetDateTime from,
        OffsetDateTime to) {
        return db.list(
            "SELECT starts_at,ends_at FROM consultation_bookings WHERE candidate_user_id=? "
                + "AND status IN ('PENDING','ACCEPTED') AND starts_at<? AND ends_at>?",
            candidate,
            to,
            from
        );
    }

    /** Called under the same consultant lock as shift assignment and booking creation. */
    public void requireAssignedSlot(
        UUID consultant,
        OffsetDateTime start,
        OffsetDateTime end,
        int minutes) {
        var shifts = db.list(
            "SELECT * FROM consultant_work_shifts WHERE consultant_user_id=? "
                + "AND cancelled_at IS NULL AND starts_at<=? AND ends_at>=?",
            consultant,
            start,
            end
        );
        boolean assigned = shifts.stream()
            .flatMap(
                shift -> com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.WorkShiftSlots
                    .generate(shift, minutes).stream()
            ).anyMatch(slot -> slot.startsAt().isEqual(start) && slot.endsAt().isEqual(end));
        if (!assigned)
            throw ApiException.conflict("Choose an available slot from the assigned work schedule");
        if (busyOverlapsBooking(consultant, start.minusMinutes(15), end.plusMinutes(15)))
            throw ApiException.conflict("A 15 minute break is required between consultations");
    }

    public Map<String, Object> credit(
        UUID id,
        UUID candidate) {
        return db.one("SELECT * FROM consultation_orders WHERE id=? AND candidate_user_id=? FOR UPDATE", id, candidate);
    }

    public void reserveCredit(
        UUID id) {
        if (db.update(
            ("UPDATE consultation_orders SET " + "sessions_remaining=sessions_remaining-1 WHERE id=? AND status='PAID' "
                + "AND is_session_pack AND sessions_remaining>0"),
            id
        ) != 1) {
            throw ApiException.conflict("No paid consultation sessions remain");
        }
    }

    public void refundCredit(
        UUID id) {
        db.one("SELECT id FROM consultation_orders WHERE id=? FOR UPDATE", id);
        db.update(
            ("UPDATE consultation_orders SET " + "sessions_remaining=LEAST(sessions_total,sessions_remaining+1) WHERE "
                + "id=?"),
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
        db.update(
            ("UPDATE consultation_bookings SET status='ACCEPTED',accepted_at=now(),"
                + "meeting_status='PENDING',meeting_next_attempt_at=now(),"
                + "meeting_error=NULL WHERE id=? AND status='PENDING'"),
            id
        );
    }

    public void retryMeeting(
        UUID id) {
        db.update(
            ("UPDATE consultation_bookings SET meeting_status=CASE WHEN "
                + "meeting_status='READY' THEN 'READY' ELSE 'PENDING' END,"
                + "meeting_next_attempt_at=now(),meeting_error=NULL WHERE id=? AND "
                + "status='ACCEPTED' AND ends_at>now() AND meeting_ended_at IS NULL"),
            id
        );
    }

    public void end(
        UUID id,
        String status,
        boolean refund) {
        db.update(
            ("UPDATE consultation_bookings SET status=?,credit_refunded=?,"
                + "completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE completed_at END,"
                + "meeting_status=CASE WHEN meeting_status='NOT_REQUESTED' THEN "
                + "meeting_status WHEN ?='COMPLETED' THEN 'ENDED' ELSE 'CANCELLED' END,"
                + "meeting_next_attempt_at=now() WHERE id=?"),
            status,
            refund,
            status,
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
            ("UPDATE consultation_threads SET status='CLOSED'," + "closed_at=COALESCE(closed_at,now()) WHERE id=?"),
            thread
        );
    }

    public boolean hasRating(
        UUID bookingId) {
        return !db.optional("SELECT id FROM consultation_ratings WHERE booking_id=?", bookingId).isEmpty();
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
            ("SELECT id FROM consultation_bookings WHERE (status='ACCEPTED' AND "
                + "ends_at<=now()) OR (status='PENDING' AND starts_at<=now()) ORDER BY " + "starts_at LIMIT 100")
        );
    }
}
