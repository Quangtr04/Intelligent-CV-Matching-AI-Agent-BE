package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RatingAppealService {

    private static final String APPEAL_DETAIL_SQL =
        "SELECT a.*,r.rating,r.comment,r.booking_id,r.is_removed,r.created_at AS rating_created_at,"
            + "b.target_role,b.subject,b.description AS booking_description,b.starts_at,b.ends_at,"
            + "b.duration_minutes,b.status AS booking_status,b.meeting_status,b.meeting_opened_at,"
            + "b.meeting_ended_at,b.thread_id,c.full_name AS consultant_name,c.email AS consultant_email,"
            + "u.full_name AS candidate_name,u.email AS candidate_email,"
            + "sr.full_name AS staff_reviewer_name,mr.full_name AS manager_reviewer_name "
            + "FROM consultation_rating_appeals a " + "JOIN consultation_ratings r ON r.id=a.rating_id "
            + "JOIN consultation_bookings b ON b.id=r.booking_id " + "JOIN users c ON c.id=a.consultant_user_id "
            + "JOIN users u ON u.id=r.candidate_user_id " + "LEFT JOIN users sr ON sr.id=a.staff_reviewer_user_id "
            + "LEFT JOIN users mr ON mr.id=a.reviewer_user_id ";

    private final SqlStore db;

    private final CurrentUser user;

    public RatingAppealService(
        SqlStore db,
        CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    public Object forBooking(
        UUID bookingId) {
        user.requireRole("CAREER_CONSULTANT");
        db.one("SELECT id FROM consultation_bookings WHERE id=? AND consultant_user_id=?", bookingId, user.id());
        List<Map<String, Object>> appeals = db.list(
            "SELECT a.* FROM consultation_rating_appeals a JOIN consultation_ratings r ON r.id=a.rating_id "
                + "WHERE r.booking_id=? AND a.consultant_user_id=?",
            bookingId,
            user.id()
        );
        return appeals.stream().map(this::withHistory).toList();
    }

    @Transactional
    public Object create(
        UUID bookingId,
        String reason,
        String evidenceUrl) {
        user.requireRole("CAREER_CONSULTANT");
        reason = required(reason, 5000);
        evidenceUrl = required(evidenceUrl, 2048);
        if (!validEvidenceUrl(evidenceUrl))
            throw ApiException.badRequest("Evidence must be an HTTPS Google Drive link");
        Map<String, Object> rating = db.one(
            "SELECT id FROM consultation_ratings WHERE booking_id=? "
                + "AND consultant_user_id=? AND NOT is_removed FOR UPDATE",
            bookingId,
            user.id()
        );
        UUID ratingId = (UUID) rating.get("id");
        if (!db.optional("SELECT id FROM consultation_rating_appeals WHERE rating_id=?", ratingId).isEmpty())
            throw ApiException.conflict("This rating already has an appeal");
        UUID id = db.insert(
            "consultation_rating_appeals",
            Map.of(
                "rating_id",
                ratingId,
                "consultant_user_id",
                user.id(),
                "reason",
                reason,
                "evidence_url",
                evidenceUrl
            )
        );
        recordHistory(id, "CAREER_CONSULTANT", "SUBMISSION", null, "PENDING", "SUBMITTED", reason);
        audit(id, "SUBMIT_RATING_APPEAL");
        return withHistory(db.one("SELECT * FROM consultation_rating_appeals WHERE id=?", id));
    }

    public Object list() {
        user.requireStaffOrOperationsRole();
        return list(null);
    }

    public Object list(
        String status) {
        user.requireStaffOrOperationsRole();
        String normalized = normalizeStatusFilter(status);
        return db.list(
            APPEAL_DETAIL_SQL + "WHERE (?::varchar IS NULL OR a.status=?) "
                + "ORDER BY (a.status IN ('PENDING','STAFF_APPROVED')) DESC,a.created_at DESC",
            normalized,
            normalized
        );
    }

    public Object listForManager(
        String status) {
        user.requireOperationsRole();
        String normalized = normalizeStatusFilter(status);
        return db.list(
            APPEAL_DETAIL_SQL + "WHERE (?::varchar IS NULL OR a.status=?) "
                + "ORDER BY (a.status='STAFF_APPROVED') DESC,(a.status='PENDING') DESC,a.created_at DESC",
            normalized,
            normalized
        );
    }

    public Map<String, Object> detail(
        UUID id) {
        user.id();
        Map<String, Object> appeal = db.one(APPEAL_DETAIL_SQL + "WHERE a.id=?", id);
        if (!user.hasStaffOrOperationsRole()) {
            user.requireRole("CAREER_CONSULTANT");
            if (!user.id().equals(appeal.get("consultant_user_id"))) {
                throw ApiException.notFound("Appeal not found");
            }
        }
        return withHistory(appeal);
    }

    public List<Map<String, Object>> history(
        UUID id) {
        detail(id);
        return db.list(
            "SELECT h.*,u.full_name AS actor_name,u.email AS actor_email "
                + "FROM consultation_rating_appeal_history h LEFT JOIN users u ON u.id=h.actor_user_id "
                + "WHERE h.appeal_id=? ORDER BY h.created_at ASC,h.id ASC",
            id
        );
    }

    @Transactional
    public Object preliminaryReview(
        UUID id,
        String decision,
        String decisionReason) {
        user.requireStaffOrOperationsRole();
        decisionReason = required(decisionReason, 5000);
        String normalized = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        if ("ACCEPTED".equals(normalized) || "APPROVE".equals(normalized) || "FORWARD".equals(normalized)) {
            normalized = "APPROVED";
        } else if ("REJECT".equals(normalized)) {
            normalized = "REJECTED";
        }
        if (!List.of("APPROVED", "REJECTED").contains(normalized))
            throw ApiException.badRequest("Invalid preliminary appeal decision; use APPROVED or REJECTED");
        Map<String, Object> appeal = db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id);
        String currentStatus = String.valueOf(appeal.get("status"));
        if (!"PENDING".equals(currentStatus))
            throw ApiException.conflict("Appeal has already passed preliminary review");
        if (user.id().equals(appeal.get("consultant_user_id")))
            throw ApiException.forbidden("Cannot review your own appeal");
        String nextStatus = "APPROVED".equals(normalized) ? "STAFF_APPROVED" : "STAFF_REJECTED";
        db.update(
            "UPDATE consultation_rating_appeals SET status=?,staff_decision=?,staff_decision_reason=?,"
                + "staff_reviewer_user_id=?,staff_reviewed_at=now(),"
                + "forwarded_to_manager_at=CASE WHEN ?='STAFF_APPROVED' THEN now() ELSE forwarded_to_manager_at END "
                + "WHERE id=?",
            nextStatus,
            normalized,
            decisionReason,
            user.id(),
            nextStatus,
            id
        );
        recordHistory(id, actorRole(), "PRELIMINARY_REVIEW", currentStatus, nextStatus, normalized, decisionReason);
        UUID consultantId = (UUID) appeal.get("consultant_user_id");
        if ("STAFF_APPROVED".equals(nextStatus)) {
            notifyConsultant(
                consultantId,
                id,
                "RATING_APPEAL_STAFF_APPROVED",
                "Khiếu nại đánh giá đã qua vòng sơ duyệt",
                "Nhân viên đã xác minh và chuyển báo cáo khiếu nại đánh giá của bạn lên Quản lý để phê duyệt cuối cùng."
            );
            notifyManagers(
                id,
                "RATING_APPEAL_FORWARDED",
                "Báo cáo khiếu nại đánh giá chờ phê duyệt cuối cùng",
                "Nhân viên đã sơ duyệt và chuyển tiếp báo cáo khiếu nại đánh giá. Lý do sơ duyệt: " + decisionReason
            );
        } else {
            notifyConsultant(
                consultantId,
                id,
                "RATING_APPEAL_STAFF_REJECTED",
                "Khiếu nại đánh giá bị từ chối ở vòng sơ duyệt",
                "Lý do: " + decisionReason
            );
        }
        audit(id, "STAFF_REVIEW_RATING_APPEAL_" + normalized);
        return withHistory(db.one("SELECT * FROM consultation_rating_appeals WHERE id=?", id));
    }

    @Transactional
    public Object decide(
        UUID id,
        String decision,
        String decisionReason) {
        user.requireOperationsRole();
        decisionReason = required(decisionReason, 5000);
        String normalized = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(normalized) || "APPROVE".equals(normalized)) {
            normalized = "ACCEPTED";
        } else if ("REJECT".equals(normalized)) {
            normalized = "REJECTED";
        }
        if (!List.of("ACCEPTED", "REJECTED").contains(normalized))
            throw ApiException.badRequest("Invalid appeal decision");
        Map<String, Object> appeal = db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id);
        String currentStatus = String.valueOf(appeal.get("status"));
        if (!Set.of("PENDING", "STAFF_APPROVED").contains(currentStatus))
            throw ApiException.conflict("Appeal has already been reviewed");
        if (user.id().equals(appeal.get("consultant_user_id")))
            throw ApiException.forbidden("Cannot review your own appeal");
        if ("ACCEPTED".equals(normalized)) {
            db.update("UPDATE consultation_ratings SET is_removed=true WHERE id=?", appeal.get("rating_id"));
        }
        db.update(
            "UPDATE consultation_rating_appeals SET status=?,decision_reason=?,reviewer_user_id=?,"
                + "reviewed_at=now() WHERE id=?",
            normalized,
            decisionReason,
            user.id(),
            id
        );
        recordHistory(id, actorRole(), "FINAL_REVIEW", currentStatus, normalized, normalized, decisionReason);
        Object consultantObj = appeal.get("consultant_user_id");
        if (consultantObj instanceof UUID consultantId) {
            notifyConsultant(
                consultantId,
                id,
                "RATING_APPEAL_" + normalized,
                "ACCEPTED".equals(normalized)
                    ? "Khiếu nại đánh giá đã được chấp thuận"
                    : "Khiếu nại đánh giá đã bị từ chối",
                "Quyết định cuối cùng: " + decisionReason
            );
        }
        audit(id, "REVIEW_RATING_APPEAL_" + normalized);
        return withHistory(db.one("SELECT * FROM consultation_rating_appeals WHERE id=?", id));
    }

    private Map<String, Object> withHistory(
        Map<String, Object> appeal) {
        if (appeal == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>(appeal);
        Object idObj = copy.get("id");
        if (idObj instanceof UUID appealId) {
            List<Map<String, Object>> history = db.list(
                "SELECT h.*,u.full_name AS actor_name "
                    + "FROM consultation_rating_appeal_history h LEFT JOIN users u ON u.id=h.actor_user_id "
                    + "WHERE h.appeal_id=? ORDER BY h.created_at ASC,h.id ASC",
                appealId
            );
            copy.put("history", history == null ? List.of() : history);
        }
        return copy;
    }

    private void recordHistory(
        UUID appealId,
        String actorRole,
        String stage,
        String fromStatus,
        String toStatus,
        String decision,
        String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appeal_id", appealId);
        row.put("actor_user_id", user.id());
        row.put("actor_role", actorRole);
        row.put("stage", stage);
        row.put("from_status", fromStatus);
        row.put("to_status", toStatus);
        row.put("decision", decision);
        row.put("reason", reason);
        db.insert("consultation_rating_appeal_history", row);
    }

    private void notifyConsultant(
        UUID consultantId,
        UUID appealId,
        String type,
        String title,
        String content) {
        if (consultantId == null) {
            return;
        }
        db.insert(
            "notifications",
            Map.of(
                "user_id",
                consultantId,
                "type",
                type,
                "title",
                title,
                "content",
                content,
                "reference_type",
                "RATING_APPEAL",
                "reference_id",
                appealId
            )
        );
    }

    private void notifyManagers(
        UUID appealId,
        String type,
        String title,
        String content) {
        db.update(
            "INSERT INTO notifications(user_id,type,title,content,reference_type,reference_id) "
                + "SELECT id,?,?,?,'RATING_APPEAL',? FROM users WHERE role IN ('MANAGER','ADMIN') AND status='ACTIVE'",
            type,
            title,
            content,
            appealId
        );
    }

    private String actorRole() {
        if (user.isStrictAdmin())
            return "ADMIN";
        if (user.hasRole("MANAGER"))
            return "MANAGER";
        if (user.hasRole("STAFF"))
            return "STAFF";
        return "CAREER_CONSULTANT";
    }

    private static String normalizeStatusFilter(
        String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "STAFF_APPROVED", "STAFF_REJECTED", "ACCEPTED", "REJECTED").contains(normalized)) {
            throw ApiException.badRequest("Invalid rating appeal status filter");
        }
        return normalized;
    }

    static boolean validEvidenceUrl(
        String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && "drive.google.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getPath() != null
                && !uri.getPath().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String required(
        String value,
        int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw ApiException.badRequest("Required text is missing or too long");
        return value.strip();
    }

    private void audit(
        UUID id,
        String action) {
        db.insert(
            "audit_logs",
            Map.of("actor_user_id", user.id(), "action", action, "entity_type", "RATING_APPEAL", "entity_id", id)
        );
    }
}
