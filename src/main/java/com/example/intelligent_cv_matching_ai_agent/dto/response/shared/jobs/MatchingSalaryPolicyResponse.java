package com.example.intelligent_cv_matching_ai_agent.dto.response.shared.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MatchingSalaryPolicyResponse(
    BigDecimal tolerancePercent,
    BigDecimal minimumRatio,
    long version,
    String policyVersion,
    Instant updatedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    UUID updatedBy
) {
}
