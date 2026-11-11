package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.ManagerQuotaLimitsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionQuotaRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerQuotaRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagerQuotaPolicy {

    private final ManagerQuotaRepository db;

    private final CurrentUser current;

    private final AuditService audit;

    private final long defaultTokens;

    private final long defaultCalls;

    public ManagerQuotaPolicy(
        ManagerQuotaRepository db,
        CurrentUser current,
        AuditService audit,
        @Value("${app.manager.default-max-token-quota:10000000}")
        long defaultTokens,
        @Value("${app.manager.default-max-api-quota:100000}")
        long defaultCalls) {
        if (defaultTokens < 0 || defaultCalls < 0) {
            throw new IllegalArgumentException("Manager quota ceilings must be nonnegative");
        }
        this.db = db;
        this.current = current;
        this.audit = audit;
        this.defaultTokens = defaultTokens;
        this.defaultCalls = defaultCalls;
    }

    public Map<String, Object> ownLimits() {
        current.requireOperationsRole();
        return limits(current.id());
    }

    public Map<String, Object> managerLimits(
        UUID manager) {
        current.requireRole("ADMIN");
        db.requireManager(manager);
        return limits(manager);
    }

    private Map<String, Object> limits(
        UUID manager) {
        var saved = db.limits(manager);
        List<String> codes = new ArrayList<>(List.of("AI_TOTAL_TOKENS", "API_CALLS_TOTAL"));
        for (Map<String, Object> feature : AiFeatureCatalog.descriptors()) {
            String code = feature.get("featureCode").toString();
            codes.add(code);
            codes.add("API_CALLS_" + code);
        }
        return Map.of(
            "maxTokenQuota",
            saved.map(row -> ((Number) row.get("max_token_quota")).longValue()).orElse(defaultTokens),
            "maxApiQuota",
            saved.map(row -> ((Number) row.get("max_api_quota")).longValue()).orElse(defaultCalls),
            "allowedFeatureCodes",
            codes,
            "allowedPeriods",
            List.of("MONTHLY", "SUBSCRIPTION"),
            "source",
            saved.isPresent() ? "ADMIN_OVERRIDE" : "DEFAULT_POLICY"
        );
    }

    public void validate(
        List<SubscriptionQuotaRequest> quotas) {
        current.requireOperationsRole();
        if (current.isStrictAdmin()) {
            return;
        }
        Map<String, Object> limits = ownLimits();
        List<?> codes = (List<?>) limits.get("allowedFeatureCodes");
        List<?> periods = (List<?>) limits.get("allowedPeriods");
        for (SubscriptionQuotaRequest quota : quotas) {
            long ceiling =
                ((Number) limits.get(quota.featureCode().startsWith("API_CALLS_") ? "maxApiQuota" : "maxTokenQuota"))
                    .longValue();
            if (!codes.contains(quota.featureCode()) || !periods.contains(quota.quotaPeriod()) || quota.quotaValue() < 0
                || quota.quotaValue() > ceiling) {
                throw ApiException.forbidden("Quota exceeds this manager's delegated limits");
            }
        }
    }

    @Transactional
    public Map<String, Object> update(
        UUID manager,
        ManagerQuotaLimitsRequest body) {
        current.requireRole("ADMIN");
        db.requireManager(manager);
        if (body.maxTokenQuota() < 0 || body.maxApiQuota() < 0) {
            throw ApiException.badRequest("Quota ceilings must be nonnegative");
        }
        Map<String, Object> before = limits(manager);
        db.save(manager, body.maxTokenQuota(), body.maxApiQuota(), current.id());
        Map<String, Object> after = limits(manager);
        audit.record("UPDATE_MANAGER_QUOTA_LIMITS", "USER", manager, before, after);
        return after;
    }
}
