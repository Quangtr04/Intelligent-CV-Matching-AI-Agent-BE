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
        return db.list(
            "SELECT a.* FROM consultation_rating_appeals a JOIN consultation_ratings r ON r.id=a.rating_id "
                + "WHERE r.booking_id=? AND a.consultant_user_id=?",
            bookingId,
            user.id()
        );
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
        audit(id, "SUBMIT_RATING_APPEAL");
        return db.one("SELECT * FROM consultation_rating_appeals WHERE id=?", id);
    }

    public Object list() {
        user.requireOperationsRole();
        return db.list(
            "SELECT a.*,r.rating,r.comment,r.booking_id,c.full_name AS consultant_name,"
                + "u.full_name AS candidate_name FROM consultation_rating_appeals a "
                + "JOIN consultation_ratings r ON r.id=a.rating_id JOIN users c ON c.id=a.consultant_user_id "
                + "JOIN users u ON u.id=r.candidate_user_id ORDER BY (a.status='PENDING') DESC,a.created_at DESC"
        );
    }

    @Transactional
    public Object decide(
        UUID id,
        String decision,
        String decisionReason) {
        user.requireOperationsRole();
        decisionReason = required(decisionReason, 5000);
        if (!List.of("ACCEPTED", "REJECTED").contains(decision == null ? "" : decision))
            throw ApiException.badRequest("Invalid appeal decision");
        Map<String, Object> appeal = db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id);
        if (!"PENDING".equals(appeal.get("status")))
            throw ApiException.conflict("Appeal has already been reviewed");
        if (user.id().equals(appeal.get("consultant_user_id")))
            throw ApiException.forbidden("Cannot review your own appeal");
        if ("ACCEPTED".equals(decision)) {
            db.update("UPDATE consultation_ratings SET is_removed=true WHERE id=?", appeal.get("rating_id"));
        }
        db.update(
            "UPDATE consultation_rating_appeals SET status=?,decision_reason=?,reviewer_user_id=?,"
                + "reviewed_at=now() WHERE id=?",
            decision,
            decisionReason,
            user.id(),
            id
        );
        audit(id, "REVIEW_RATING_APPEAL_" + decision);
        return db.one("SELECT * FROM consultation_rating_appeals WHERE id=?", id);
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
