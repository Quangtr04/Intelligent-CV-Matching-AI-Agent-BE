package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.SalaryToleranceDeserializer;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

public record MatchingSalaryPolicyRequest(
    @NotNull
    @DecimalMin("0")
    @DecimalMax("100")
    @Digits(integer = 3, fraction = 2)
    @JsonDeserialize(using = SalaryToleranceDeserializer.class)
    BigDecimal tolerancePercent
) {
}
