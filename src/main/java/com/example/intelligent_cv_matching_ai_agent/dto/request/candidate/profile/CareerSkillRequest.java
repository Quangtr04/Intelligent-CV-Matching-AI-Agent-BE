package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Validated input for the identity API. */
public record CareerSkillRequest(
    @NotNull
    UUID skillId,
    @Size(max = 50)
    String proficiencyLevel,
    @DecimalMin("0")
    @DecimalMax("99.9")
    @Digits(integer = 3, fraction = 1)
    BigDecimal yearsExperience
) {
}
