package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationRatingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantBusyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class ConsultationBookingService {

    private final ConsultationBookingRepository db;

    private final CurrentUser user;

    public ConsultationBookingService(
        ConsultationBookingRepository db,
        CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    public List<Map<String, Object>> consultants(
        String targetRole) {
        user.id();
        String role = normalizeRole(targetRole);
        return db.consultants().stream().filter(c -> role.isEmpty() || supports(c, role)).toList();
    }

    public List<Map<String, Object>> availability(
        UUID consultant,
        OffsetDateTime from,
        OffsetDateTime to) {
        user.id();
        if (!to.isAfter(from) || Duration.between(from, to).toDays() > 93) {
            throw ApiException.badRequest("Availability range must be positive and no longer than 93 days");
        }
        db.consultant(consultant);
        return db.availability(consultant, from, to);
    }

    public Map<String, Object> profile() {
        user.requireRole("CAREER_CONSULTANT");
        return db.profile(user.id());
    }

    @Transactional
    public Map<String, Object> saveProfile(
        ConsultantProfileRequest input) {
        user.requireRole("CAREER_CONSULTANT");
        List<String> roles = new ArrayList<>();
        Set<String> canonical = new HashSet<>();
        if (input.targetRoles() == null || input.targetRoles().isEmpty() || input.targetRoles().size() > 20) {
            throw ApiException.badRequest("Select between 1 and 20 consultation roles");
        }
        for (String role : input.targetRoles()) {
            if (role == null || role.isBlank() || role.strip().length() > 150) {
                throw ApiException.badRequest("Each consultation role must contain 1 to 150 characters");
            }
            if (canonical.add(normalizeRole(role))) {
                roles.add(role.strip());
            }
        }
        db.lockConsultant(user.id());
        return db.saveProfile(user.id(), roles, text(input.bio()).strip());
    }

    public List<Map<String, Object>> busy() {
        user.requireRole("CAREER_CONSULTANT");
        return db.busy(user.id());
    }

    @Transactional
    public Map<String, Object> addBusy(
        ConsultantBusyRequest input) {
        throw ApiException.forbidden("Working hours are assigned by a manager");
    }

    @Transactional
    public Map<String, Object> removeBusy(
        UUID id) {
        throw ApiException.forbidden("Working hours are assigned by a manager");
    }

    @Transactional
    public Map<String, Object> create(
        ConsultationBookingRequest input) {
        user.requireRole("CANDIDATE");
        int durationMinutes = input.durationMinutes() == null ? 60 : input.durationMinutes();
        if (durationMinutes != 30 && durationMinutes != 60) {
            throw ApiException.badRequest("Consultations must last 30 or 60 minutes");
        }
        if (input.startsAt() == null || !input.startsAt().isAfter(OffsetDateTime.now())) {
            throw ApiException.badRequest("Consultation must start in the future");
        }
        OffsetDateTime end = input.startsAt().plusMinutes(durationMinutes);
        UUID candidate = user.id();
        db.lockPeople(candidate, input.consultantId());
        db.requireAssignedSlot(input.consultantId(), input.startsAt(), end, durationMinutes);
        if (input.resumeId() == null) {
            throw ApiException.badRequest("Select a confirmed CV for this consultation");
        }
        db.requireReadyResume(input.resumeId(), candidate);
        if (input.jobId() != null) {
            db.requireActiveJob(input.jobId());
        }
        if (!supports(db.consultant(input.consultantId()), normalizeRole(input.targetRole()))) {
            throw ApiException.badRequest("Selected consultant does not support the target role");
        }
        if (db.overlaps(candidate, input.consultantId(), input.startsAt(), end)) {
            throw ApiException.conflict("The candidate or consultant is unavailable during this time");
        }
        Map<String, Object> credit = db.credit(input.creditOrderId(), candidate);
        if (!"PAID".equals(credit.get("status")) || !Boolean.TRUE.equals(credit.get("is_session_pack"))
            || !"LIVE".equals(credit.get("delivery_mode")) || number(credit.get("sessions_remaining"), 0) < 1) {
            throw ApiException.conflict("Purchase a consultation session pack with remaining sessions first");
        }
        db.reserveCredit(input.creditOrderId());
        UUID thread = db.createThread(
            fields(
                "candidate_user_id",
                candidate,
                "consultant_user_id",
                input.consultantId(),
                "subject",
                input.subject().strip(),
                "request_type",
                "SESSION_BOOKING",
                "resume_id",
                input.resumeId(),
                "job_id",
                input.jobId(),
                "request_description",
                input.description().strip(),
                "priority",
                "NORMAL",
                "status",
                "OPEN"
            )
        );
        UUID id = db.insert(
            fields(
                "candidate_user_id",
                candidate,
                "consultant_user_id",
                input.consultantId(),
                "credit_order_id",
                input.creditOrderId(),
                "resume_id",
                input.resumeId(),
                "job_id",
                input.jobId(),
                "target_role",
                input.targetRole().strip(),
                "subject",
                input.subject().strip(),
                "description",
                input.description().strip(),
                "starts_at",
                input.startsAt(),
                "ends_at",
                end,
                "duration_minutes",
                durationMinutes,
                "status",
                "PENDING",
                "thread_id",
                thread
            )
        );
        db.inform(
            input.consultantId(),
            thread,
            "CONSULTATION_BOOKING_REQUEST",
            "Yêu cầu đặt lịch tư vấn mới",
            input.subject() + " — " + input.startsAt() + ". Vui lòng xem xét tiếp nhận."
        );
        return db.booking(id);
    }

    public List<Map<String, Object>> bookings() {
        return db.bookings(user.id(), user.hasRole("ADMIN"));
    }

    @Transactional
    public Map<String, Object> accept(
        UUID id) {
        user.requireRole("CAREER_CONSULTANT");
        Map<String, Object> row = lockOwned(id, "consultant_user_id");
        if ("ACCEPTED".equals(row.get("status"))) {
            return db.booking(id);
        }
        requireStatus(row, "PENDING");
        requireBeforeStart(row);
        db.accept(id);
        db.inform(
            uuid(row.get("candidate_user_id")),
            uuid(row.get("thread_id")),
            "CONSULTATION_BOOKING_ACCEPTED",
            "Lịch tư vấn đã được tiếp nhận",
            text(row.get("subject")) + " — " + row.get("starts_at")
                + ". Hệ thống đang tạo Google Meet và gửi lời mời Google Calendar cho hai bên."
        );
        return db.booking(id);
    }

    @Transactional
    public Map<String, Object> retryMeeting(
        UUID id) {
        Map<String, Object> before = db.booking(id);
        UUID viewer = user.id();
        if (!user.hasRole("ADMIN") && !viewer.equals(before.get("candidate_user_id"))
            && !viewer.equals(before.get("consultant_user_id"))) {
            throw ApiException.notFound("Booking not found");
        }
        db.lockPeople(uuid(before.get("candidate_user_id")), uuid(before.get("consultant_user_id")));
        Map<String, Object> row = db.lockBooking(id);
        requireStatus(row, "ACCEPTED");
        if (!time(row.get("ends_at")).isAfter(OffsetDateTime.now())) {
            throw ApiException.conflict("The scheduled consultation has ended");
        }
        db.retryMeeting(id);
        return db.booking(id);
    }

    @Transactional
    public Map<String, Object> decline(
        UUID id) {
        user.requireRole("CAREER_CONSULTANT");
        Map<String, Object> row = lockOwned(id, "consultant_user_id");
        requireStatus(row, "PENDING");
        requireBeforeStart(row);
        end(row, "DECLINED", true);
        return db.booking(id);
    }

    @Transactional
    public Map<String, Object> cancel(
        UUID id) {
        user.requireRole("CANDIDATE");
        Map<String, Object> row = lockOwned(id, "candidate_user_id");
        if (!Set.of("PENDING", "ACCEPTED").contains(text(row.get("status")))) {
            throw ApiException.conflict("Only a pending or accepted booking can be cancelled");
        }
        requireBeforeStart(row);
        end(row, "CANCELLED", true);
        return db.booking(id);
    }

    @Transactional
    public Map<String, Object> complete(
        UUID id) {
        user.requireRole("CAREER_CONSULTANT");
        Map<String, Object> row = lockOwned(id, "consultant_user_id");
        if ("COMPLETED".equals(row.get("status"))) {
            return db.booking(id);
        }
        requireStatus(row, "ACCEPTED");
        requireEnded(row);
        finishScheduledSession(row);
        return db.booking(id);
    }

    @Transactional
    public Map<String, Object> rate(
        UUID id,
        ConsultationRatingRequest input) {
        user.requireRole("CANDIDATE");
        Map<String, Object> row = lockOwned(id, "candidate_user_id");
        requireStatus(row, "COMPLETED");
        requireEnded(row);
        if (input.rating() == null || input.rating() < 1 || input.rating() > 5 || input.comment() == null
            || input.comment().isBlank() || input.comment().length() > 2000) {
            throw ApiException.badRequest("Provide a rating from 1 to 5 and a review of 1 to 2000 characters");
        }
        if (db.booking(id).get("rating") != null) {
            throw ApiException.conflict("This consultation has already been reviewed");
        }
        db.rate(
            fields(
                "booking_id",
                id,
                "candidate_user_id",
                user.id(),
                "consultant_user_id",
                row.get("consultant_user_id"),
                "rating",
                input.rating(),
                "comment",
                input.comment().strip()
            )
        );
        return db.booking(id);
    }

    @Transactional
    public void settleDue(
        UUID id) {
        Map<String, Object> before = db.booking(id);
        // No HTTP/user context: only the maintenance component invokes this method.
        db.lockPeople(uuid(before.get("candidate_user_id")), uuid(before.get("consultant_user_id")));
        Map<String, Object> row = db.lockBooking(id);
        OffsetDateTime now = OffsetDateTime.now();
        if ("ACCEPTED".equals(row.get("status")) && !time(row.get("ends_at")).isAfter(now)) {
            finishScheduledSession(row);
        } else if ("PENDING".equals(row.get("status")) && !time(row.get("starts_at")).isAfter(now)) {
            // A request that was never accepted must not consume a candidate's paid session.
            end(row, "DECLINED", true);
        }
    }

    private Map<String, Object> lockOwned(
        UUID id,
        String ownerField) {
        Map<String, Object> before = db.booking(id);
        user.owns(before, ownerField);
        db.lockPeople(uuid(before.get("candidate_user_id")), uuid(before.get("consultant_user_id")));
        Map<String, Object> row = db.lockBooking(id);
        user.owns(row, ownerField);
        return row;
    }

    private void finishScheduledSession(
        Map<String, Object> row) {
        boolean failedDelivery = row.get("meeting_status") != null && !"NOT_REQUESTED".equals(row.get("meeting_status"))
            && row.get("meeting_opened_at") == null;
        end(row, failedDelivery ? "DECLINED" : "COMPLETED", failedDelivery);
    }

    private void end(
        Map<String, Object> row,
        String status,
        boolean refund) {
        UUID thread = uuid(row.get("thread_id"));
        if (refund && !Boolean.TRUE.equals(row.get("credit_refunded"))) {
            db.refundCredit(uuid(row.get("credit_order_id")));
        }
        db.end(uuid(row.get("id")), status, refund);
        db.closeThread(thread);
        if ("COMPLETED".equals(status)) {
            db.inform(
                uuid(row.get("candidate_user_id")),
                thread,
                "CONSULTATION_RATING_REQUEST",
                "Đánh giá buổi tư vấn của bạn",
                text(row.get("subject")) + ". Hãy chấm 1–5 sao và viết nhận xét."
            );
        } else {
            String message = text(row.get("subject")) + ". Lượt tư vấn đã được hoàn lại vào gói.";
            db.inform(
                uuid(row.get("candidate_user_id")),
                thread,
                "CONSULTATION_BOOKING_" + status,
                "Lịch tư vấn đã bị hủy hoặc từ chối",
                message
            );
            db.inform(
                uuid(row.get("consultant_user_id")),
                thread,
                "CONSULTATION_BOOKING_" + status,
                "Lịch tư vấn đã bị hủy hoặc từ chối",
                text(row.get("subject"))
            );
        }
    }

    private static void requireStatus(
        Map<String, Object> row,
        String status) {
        if (!status.equals(row.get("status"))) {
            throw ApiException.conflict("Booking must have status " + status);
        }
    }

    private static void requireBeforeStart(
        Map<String, Object> row) {
        if (!time(row.get("starts_at")).isAfter(OffsetDateTime.now())) {
            throw ApiException.conflict("The scheduled consultation has already started");
        }
    }

    private static void requireEnded(
        Map<String, Object> row) {
        if (time(row.get("ends_at")).isAfter(OffsetDateTime.now())) {
            throw ApiException.conflict("The scheduled consultation has not ended yet");
        }
    }

    private static OffsetDateTime time(
        Object value) {
        if (value instanceof OffsetDateTime t) {
            return t;
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Instant t) {
            return t.atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(value.toString());
    }

    private static boolean supports(
        Map<String, Object> consultant,
        String role) {
        return !role.isEmpty()
            && array(consultant.get("target_roles")).stream().anyMatch(r -> normalizeRole(text(r)).equals(role));
    }

    private static String normalizeRole(
        String role) {
        String value = text(role).toLowerCase(Locale.ROOT).strip().replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
        return switch (value) {
            case "be", "backend", "back end", "backend developer", "backend engineer", "back end developer",
                "back end engineer" -> "backend";
            case "fe", "frontend", "front end", "frontend developer", "frontend engineer", "front end developer",
                "front end engineer" -> "frontend";
            case "fullstack", "full stack", "fullstack developer", "full stack developer", "full stack engineer" ->
                "fullstack";
            case "qa", "qc", "tester", "quality assurance", "qa engineer", "software tester" -> "qa";
            case "devops", "devops engineer" -> "devops";
            case "ba", "business analyst" -> "business analyst";
            default -> value;
        };
    }
}
