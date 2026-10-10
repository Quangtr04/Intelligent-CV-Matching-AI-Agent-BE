package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by EntitlementService. */
@Repository
public class EntitlementRepository {

    private final SqlStore db;

    public EntitlementRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public long tokensUsed(
        UUID user,
        String feature,
        String period) {
        // Period values originate in subscription quota rules, never raw request text.
        String boundary = switch (period) {
            case "all" -> "";
            case "day", "month", "year" -> " AND created_at>=date_trunc('" + period + "',now())";
            default -> throw new IllegalArgumentException("Unsupported quota period");
        };
        String query = ("SELECT COALESCE(sum(CASE WHEN status='RESERVED' THEN reserved_tokens "
            + "ELSE consumed_tokens END),0) FROM ai_token_reservations WHERE "
            + "user_id=? AND status IN ('RESERVED','COMPLETED')") + boundary;
        Object count =
            feature == null ? db.scalar(query, user) : db.scalar(query + " AND feature_code=?", user, feature);
        return ((Number) count).longValue();
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR NO KEY UPDATE", args);
    }

    public Optional<Map<String, Object>> findSubscriptionQuotasByTierId(
        Object... args) {
        return db.optional(
            ("SELECT quota_value,quota_period FROM subscription_quotas WHERE "
                + "tier_id=? AND feature_code='AI_TOTAL_TOKENS'"),
            args
        );
    }

    public Optional<Map<String, Object>> findSubscriptionQuotasByTierId2(
        Object... args) {
        return db.optional(
            ("SELECT quota_value,quota_period FROM subscription_quotas WHERE " + "tier_id=? AND feature_code=?"),
            args
        );
    }

    public UUID insertAiTokenReservations(
        Map<String, Object> fields) {
        return db.insert("ai_token_reservations", fields);
    }

    public Optional<Map<String, Object>> findUserSubscriptionsByUserId(
        Object... args) {
        return db.optional(
            ("SELECT st.* FROM user_subscriptions us JOIN subscription_tiers st ON "
                + "st.id=us.tier_id WHERE us.user_id=? AND us.status='ACTIVE' AND "
                + "st.is_active=true AND (us.expires_at IS NULL OR us.expires_at>now()) "
                + "ORDER BY us.started_at DESC LIMIT 1"),
            args
        );
    }

    public Optional<Map<String, Object>> findSubscriptionTiersByTierCode(
        Object... args) {
        return db.optional("SELECT * FROM subscription_tiers WHERE tier_code='FREE' AND is_active=true", args);
    }

    public Map<String, Object> getAiTokenReservationsByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM ai_token_reservations WHERE id=? FOR UPDATE", args);
    }

    public int updateAiTokenReservationsById(
        Object... args) {
        return db.update("UPDATE ai_token_reservations SET consumed_tokens=?,status='COMPLETED' WHERE id=?", args);
    }

    public int updateAiTokenReservationsById2(
        Object... args) {
        return db.update(
            ("UPDATE ai_token_reservations SET status='CANCELLED' WHERE id=? AND " + "status='RESERVED'"),
            args
        );
    }

    public List<Map<String, Object>> listSubscriptionQuotasByTierId(
        Object... args) {
        return db.list(
            ("SELECT feature_code,quota_value,quota_period FROM subscription_quotas "
                + "WHERE tier_id=? ORDER BY feature_code"),
            args
        );
    }

}
