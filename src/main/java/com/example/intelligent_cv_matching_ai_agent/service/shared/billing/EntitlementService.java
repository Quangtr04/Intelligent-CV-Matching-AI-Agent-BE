package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Reservations are quota accounting; ai_usage_logs separately record provider
 * telemetry.
 */
@Service
public class EntitlementService {

    private final EntitlementRepository db;

    public EntitlementService(
        EntitlementRepository db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(
        UUID user,
        String feature,
        int tokenBudget) {
        if (tokenBudget < 1 || tokenBudget > 200000)
            throw ApiException.badRequest("Invalid AI token budget");

        var account = db.getUsersByIdForUpdate(user);
        String role = (String) account.get("role");
        // Read the persisted owner's role so background work follows the same policy.
        long subscriptionRemaining = -1;
        long walletReserved = 0;
        if (!"ADMIN".equals(role) && !"MANAGER".equals(role) && !"CAREER_CONSULTANT".equals(role)) {
            subscriptionRemaining = checkSubscriptionQuota(user, feature, tokenBudget);
            walletReserved = subscriptionRemaining < 0 ? 0 : Math.max(0, tokenBudget - subscriptionRemaining);
            if (walletReserved > 0 && db.reserveWalletTokens(user, walletReserved) == 0)
                throw new ApiException(
                    429,
                    "AI token quota exceeded; upgrade your subscription, top up tokens or wait for the next quota period"
                );
        }

        return db.insertAiTokenReservations(
            Map.of(
                "user_id",
                user,
                "feature_code",
                feature,
                "reserved_tokens",
                tokenBudget,
                "consumed_tokens",
                0,
                "status",
                "RESERVED",
                "subscription_quota_remaining",
                Math.max(0, subscriptionRemaining),
                "wallet_reserved_tokens",
                walletReserved,
                "created_at",
                OffsetDateTime.now()
            )
        );
    }

    private long checkSubscriptionQuota(
        UUID user,
        String feature,
        int tokenBudget) {
        Map<String, Object> tier = effectiveTier(user);
        var global = db.findSubscriptionQuotasByTierId(tier.get("id"));
        if (global.isEmpty())
            throw new ApiException(503, "AI quota is not configured for this subscription");

        long quota = ((Number) global.get().get("quota_value")).longValue();
        var specific = db.findSubscriptionQuotasByTierId2(tier.get("id"), feature);
        if (specific.isPresent() && ((Number) specific.get().get("quota_value")).longValue() == 0)
            throw ApiException.forbidden("Feature unavailable in this subscription");

        long used = usage(user, null, period(global.get().get("quota_period")));
        long remaining = quota < 0 ? Long.MAX_VALUE : Math.max(0, quota - used);

        if (specific.isPresent()) {
            long featureQuota = ((Number) specific.get().get("quota_value")).longValue();
            long featureUsed = usage(user, feature, period(specific.get().get("quota_period")));
            if (featureQuota >= 0 && featureUsed > featureQuota - tokenBudget)
                throw new ApiException(429, "Feature token quota exceeded");
        }

        // The user lock above makes call limits atomic with token reservations.
        checkCallQuota(user, tier.get("id"), "API_CALLS_TOTAL", null);
        checkCallQuota(user, tier.get("id"), "API_CALLS_" + feature, feature);
        if (quota < 0 || tokenBudget <= remaining)
            return quota < 0 ? -1 : remaining;
        long wallet = db.walletBalance(user);
        if (wallet < tokenBudget - remaining)
            throw new ApiException(
                429,
                "AI token quota exceeded; upgrade your subscription, top up tokens or wait for the next quota period"
            );
        return remaining;
    }

    private void checkCallQuota(
        UUID user,
        Object tier,
        String code,
        String feature) {
        var rule = db.findSubscriptionQuotasByTierId2(tier, code);
        if (rule.isEmpty()) {
            return;
        }
        long limit = ((Number) rule.get().get("quota_value")).longValue();
        if (limit >= 0 && db.requestsUsed(user, feature, period(rule.get().get("quota_period"))) >= limit) {
            throw new ApiException(429, "AI API call quota exceeded");
        }
    }

    private String period(
        Object raw) {
        String period = raw == null ? "MONTHLY" : raw.toString().toUpperCase(java.util.Locale.ROOT);

        return switch (period) {
            case "DAILY" -> "day";
            case "YEARLY" -> "year";
            case "LIFETIME" -> "all";
            case "SUBSCRIPTION" -> "subscription";
            default -> "month";
        };
    }

    private long usage(
        UUID user,
        String feature,
        String period) {
        return db.tokensUsed(user, feature, period);
    }

    public Map<String, Object> effectiveTier(
        UUID user) {
        return db.findUserSubscriptionsByUserId(user).orElseGet(
            () -> db.findSubscriptionTiersByTierCode()
                .orElseThrow(() -> new ApiException(503, "Free subscription tier is not configured"))
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(
        UUID reservation,
        int actualTokens) {
        Map<String, Object> row = db.getAiTokenReservationsByIdForUpdate(reservation);
        if (!"RESERVED".equals(row.get("status")))
            throw ApiException.conflict("Reservation already finalized");
        if (actualTokens < 0 || actualTokens > ((Number) row.get("reserved_tokens")).intValue())
            throw ApiException.badRequest("Actual AI usage exceeds the reserved budget");

        long walletReserved = ((Number) row.getOrDefault("wallet_reserved_tokens", 0)).longValue();
        long subscriptionRemaining = ((Number) row.getOrDefault("subscription_quota_remaining", 0)).longValue();
        long walletConsumed = Math.max(0, (long) actualTokens - subscriptionRemaining);
        long walletRefund = Math.max(0, walletReserved - walletConsumed);
        db.updateAiTokenReservationsById(actualTokens, reservation);
        db.refundWalletTokens((UUID) row.get("user_id"), walletRefund);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancel(
        UUID reservation) {
        Map<String, Object> row = db.getAiTokenReservationsByIdForUpdate(reservation);
        if (!"RESERVED".equals(row.get("status")))
            return;
        db.updateAiTokenReservationsById2(reservation);
        db.refundWalletTokens(
            (UUID) row.get("user_id"),
            ((Number) row.getOrDefault("wallet_reserved_tokens", 0)).longValue()
        );
    }

    public Map<String, Object> summary(
        UUID user) {
        Map<String, Object> tier = effectiveTier(user);

        return Map.of(
            "tier",
            tier,
            "quotas",
            db.listSubscriptionQuotasByTierId(tier.get("id")),
            "tokensUsedThisMonth",
            usage(user, null, tier.containsKey("subscription_started_at") ? "subscription" : "month"),
            "tokenWalletBalance",
            db.walletBalance(user),
            "apiCallsThisMonth",
            db.requestsUsed(user, null, tier.containsKey("subscription_started_at") ? "subscription" : "month")
        );
    }
}
