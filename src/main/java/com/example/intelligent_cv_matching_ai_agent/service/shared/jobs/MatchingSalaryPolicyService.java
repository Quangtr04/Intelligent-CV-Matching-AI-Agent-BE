package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.MatchingSalaryPolicyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.jobs.MatchingSalaryPolicyResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.MatchingSalarySettingsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MatchingSalaryPolicyService {

    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MatchingSalarySettingsRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    public MatchingSalaryPolicyService(
        MatchingSalarySettingsRepository db,
        CurrentUser user,
        AuditService audit) {
        this.db = db;
        this.user = user;
        this.audit = audit;
    }

    public MatchingSalaryPolicyResponse administratorPolicy() {
        user.requireOperationsRole();
        return response(effective(), true);
    }

    public MatchingSalaryPolicyResponse candidatePolicy() {
        user.requireRole("CANDIDATE");
        return response(effective(), false);
    }

    /** No cache or environment fallback: a new request observes the committed singleton settings. */
    public MatchingSalaryPolicy effective() {
        return snapshot(db.get());
    }

    @Transactional
    public MatchingSalaryPolicyResponse update(
        MatchingSalaryPolicyRequest request) {
        user.requireOperationsRole();
        BigDecimal tolerance = MatchingSalaryPolicy.validate(request == null ? null : request.tolerancePercent());
        Map<String, Object> before = db.lock();
        MatchingSalaryPolicy previous = snapshot(before);
        if (previous.tolerancePercent().compareTo(tolerance) == 0) {
            return response(previous, true);
        }
        db.update(tolerance.setScale(2), user.id());
        Map<String, Object> after = db.get();
        audit.record("UPDATE_MATCHING_SALARY_POLICY", "MATCHING_SALARY_SETTINGS", AUDIT_ID, before, after);
        return response(snapshot(after), true);
    }

    private static MatchingSalaryPolicy snapshot(
        Map<String, Object> row) {
        Object date = row.get("updated_at");
        Instant instant = date instanceof Instant value
            ? value
            : date instanceof Timestamp value
                ? value.toInstant()
                : date instanceof OffsetDateTime value ? value.toInstant() : Instant.parse(date.toString());
        return new MatchingSalaryPolicy(
            new BigDecimal(row.get("tolerance_percent").toString()),
            ((Number) row.get("version")).longValue(),
            instant,
            (UUID) row.get("updated_by")
        );
    }

    private static MatchingSalaryPolicyResponse response(
        MatchingSalaryPolicy policy,
        boolean administrator) {
        return new MatchingSalaryPolicyResponse(
            policy.tolerancePercent(),
            policy.minimumRatio(),
            policy.version(),
            MatchingSalaryPolicy.POLICY_VERSION,
            policy.updatedAt(),
            administrator ? policy.updatedBy() : null
        );
    }
}
