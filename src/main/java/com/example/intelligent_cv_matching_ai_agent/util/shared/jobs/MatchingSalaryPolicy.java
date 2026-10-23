package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One immutable settings snapshot is shared by filtering, cache keys and stored explanations. */
public record MatchingSalaryPolicy(
    BigDecimal tolerancePercent,
    long version,
    Instant updatedAt,
    UUID updatedBy
) {

    public static final String POLICY_VERSION = "candidate-configured-salary-monthly-floor-v2";

    public MatchingSalaryPolicy {
        tolerancePercent = validate(tolerancePercent).setScale(2);
        if (version < 1) {
            throw new IllegalArgumentException("Salary policy version must be positive");
        }
    }

    public BigDecimal minimumRatio() {
        return BigDecimal.ONE.subtract(tolerancePercent.movePointLeft(2));
    }

    /** Compatibility for standalone callers; application matching always supplies persisted settings. */
    public static MatchingSalaryPolicy legacyDefault() {
        return new MatchingSalaryPolicy(new BigDecimal("10.00"), 1, null, null);
    }

    public static BigDecimal validate(
        BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0 || value.scale() > 2) {
            throw ApiException.badRequest("tolerancePercent must be a number from 0 to 100 with at most two decimals");
        }
        return value;
    }
}
