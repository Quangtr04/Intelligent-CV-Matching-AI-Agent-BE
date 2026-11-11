package com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Repository
public class ConsultationMeetingRepository {

    private final SqlStore db;

    public ConsultationMeetingRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> due() {
        return db.list(
            ("SELECT id,CASE WHEN status<>'ACCEPTED' OR ends_at<=now() THEN 'CLOSE' "
                + "WHEN meeting_status='READY' THEN 'OPEN' ELSE 'PROVISION' END AS "
                + "meeting_work FROM consultation_bookings WHERE "
                + "meeting_status<>'NOT_REQUESTED' AND meeting_ended_at IS NULL AND "
                + "(meeting_lease_until IS NULL OR meeting_lease_until<=now()) AND "
                + "COALESCE(meeting_next_attempt_at,now())<=now() AND (status IN "
                + "('DECLINED','CANCELLED','COMPLETED') OR ends_at<=now() OR "
                + "(status='ACCEPTED' AND (meeting_status IN ('PENDING','FAILED') OR "
                + "(meeting_status='READY' AND meeting_opened_at IS NULL AND "
                + "starts_at<=now())))) ORDER BY CASE WHEN status<>'ACCEPTED' OR "
                + "ends_at<=now() THEN 0 WHEN meeting_status='READY' THEN 1 ELSE 2 END," + "ends_at,id LIMIT 50")
        );
    }

    @Transactional
    public boolean claim(
        UUID id,
        UUID lease) {
        return db.update(
            ("UPDATE consultation_bookings SET meeting_lease_token=?,"
                + "meeting_lease_until=now()+interval '3 minutes' WHERE id=? AND "
                + "meeting_ended_at IS NULL AND meeting_status<>'NOT_REQUESTED' AND "
                + "(meeting_lease_until IS NULL OR meeting_lease_until<=now()) AND "
                + "COALESCE(meeting_next_attempt_at,now())<=now()"),
            lease,
            id
        ) == 1;
    }

    public Map<String, Object> get(
        UUID id) {
        return db.one(
            ("SELECT b.*,c.email AS candidate_email,c.full_name AS candidate_name,"
                + "u.email AS consultant_email,u.full_name AS consultant_name FROM "
                + "consultation_bookings b JOIN users c ON c.id=b.candidate_user_id JOIN "
                + "users u ON u.id=b.consultant_user_id WHERE b.id=?"),
            id
        );
    }

    @Transactional
    public boolean renew(
        UUID id,
        UUID lease) {
        return db.update(
            ("UPDATE consultation_bookings SET meeting_lease_until=now()+interval "
                + "'3 minutes' WHERE id=? AND meeting_lease_token=? AND "
                + "meeting_lease_until>now() AND meeting_ended_at IS NULL"),
            id,
            lease
        ) == 1;
    }

    @Transactional
    public void requeueCleanup(
        UUID id) {
        // An external request can finish after an old worker loses its lease. A
        // terminal booking must be cleaned again even if another worker ended it.
        db.update(
            ("UPDATE consultation_bookings SET meeting_ended_at=NULL,"
                + "meeting_next_attempt_at=now() WHERE id=? AND (status<>'ACCEPTED' OR " + "ends_at<=now())"),
            id
        );
    }

    @Transactional
    public boolean saveSpace(
        UUID id,
        UUID lease,
        String space,
        String url,
        String eventId) {
        return db.update(
            ("UPDATE consultation_bookings SET meeting_space_name=?,meeting_url=?,"
                + "calendar_event_id=? WHERE id=? AND meeting_lease_token=? AND " + "meeting_lease_until>now()"),
            space,
            url,
            eventId,
            id,
            lease
        ) == 1;
    }

    @Transactional
    public boolean ready(
        UUID id,
        UUID lease,
        String eventId,
        String eventUrl) {
        if (db.update(
            ("UPDATE consultation_bookings SET meeting_status='READY',"
                + "calendar_event_id=?,calendar_event_url=?,meeting_error=NULL,"
                + "meeting_attempts=0,meeting_next_attempt_at=GREATEST(starts_at,now()) "
                + "WHERE id=? AND meeting_lease_token=? AND meeting_lease_until>now() "
                + "AND status='ACCEPTED' AND ends_at>now() AND meeting_status IN " + "('PENDING','FAILED')"),
            eventId,
            eventUrl,
            id,
            lease
        ) != 1) {
            return false;
        }
        Map<String, Object> row = get(id);
        for (String recipient : List.of("candidate_user_id", "consultant_user_id")) {
            db.insert(
                "notifications",
                fields(
                    "user_id",
                    row.get(recipient),
                    "type",
                    "CONSULTATION_BOOKING_MEETING_READY",
                    "title",
                    "Lịch Google Meet đã sẵn sàng",
                    "content",
                    ("Google Calendar đã được yêu cầu gửi lời mời cho hai bên. Vào Google " + "Meet đúng giờ hẹn."),
                    "reference_type",
                    "CONSULTATION_BOOKING",
                    "reference_id",
                    id,
                    "created_at",
                    OffsetDateTime.now()
                )
            );
        }
        return true;
    }

    @Transactional
    public boolean opened(
        UUID id,
        UUID lease) {
        return db.update(
            ("UPDATE consultation_bookings SET " + "meeting_opened_at=COALESCE(meeting_opened_at,now()),"
                + "meeting_next_attempt_at=ends_at,meeting_error=NULL,meeting_attempts=0 "
                + "WHERE id=? AND meeting_lease_token=? AND meeting_lease_until>now() "
                + "AND status='ACCEPTED' AND meeting_status='READY' AND starts_at<=now() " + "AND ends_at>now()"),
            id,
            lease
        ) == 1;
    }

    @Transactional
    public void ended(
        UUID id,
        UUID lease,
        boolean cancelled) {
        db.update(
            ("UPDATE consultation_bookings SET meeting_status=?," + "meeting_ended_at=now(),meeting_error=NULL,"
                + "meeting_next_attempt_at=NULL WHERE id=? AND meeting_lease_token=? AND "
                + "meeting_lease_until>now()"),
            cancelled ? "CANCELLED" : "ENDED",
            id,
            lease
        );
    }

    @Transactional
    public void failed(
        UUID id,
        UUID lease,
        String error) {
        db.update(
            ("UPDATE consultation_bookings SET meeting_status=CASE WHEN "
                + "meeting_status IN ('PENDING','FAILED') THEN 'FAILED' ELSE " + "meeting_status END,meeting_error=?,"
                + "meeting_attempts=meeting_attempts+1,meeting_next_attempt_at=CASE WHEN "
                + "status<>'ACCEPTED' OR ends_at<=now() THEN now()+interval '5 seconds' "
                + "ELSE LEAST(ends_at,now()+LEAST(300,5*power(2,LEAST(meeting_attempts,"
                + "6))) * interval '1 second') END WHERE id=? AND meeting_lease_token=? "
                + "AND meeting_lease_until>now()"),
            error,
            id,
            lease
        );
    }

    @Transactional
    public void release(
        UUID id,
        UUID lease) {
        db.update(
            ("UPDATE consultation_bookings SET meeting_lease_token=NULL,"
                + "meeting_lease_until=NULL WHERE id=? AND meeting_lease_token=?"),
            id,
            lease
        );
    }
}
