package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Validated input for the identity API. */
public record CareerProfileRequest(
    @Size(max = 255)
    String currentJobTitle,
    @Size(max = 255)
    String currentCompany,
    @Size(max = 255)
    String targetJobTitle,
    @Size(max = 10000)
    String careerGoal,
    @DecimalMin("0")
    @DecimalMax("99.9")
    @Digits(integer = 3, fraction = 1)
    BigDecimal yearsExperience,
    @DecimalMin("0")
    @Digits(integer = 12, fraction = 2)
    BigDecimal expectedSalaryMin,
    @DecimalMin("0")
    @Digits(integer = 12, fraction = 2)
    BigDecimal expectedSalaryMax,
    @Pattern(regexp = "VND|USD|EUR")
    String salaryCurrency,
    @Size(max = 255)
    String preferredLocation,
    @Size(max = 50)
    String workplacePreference,
    @Size(max = 50)
    String employmentType,
    @Size(max = 10000)
    String profileSummary,
    Map<String, Object> preferences,
    @Size(max = 100)
    List<@Valid CareerSkillRequest> skills
) {
}
