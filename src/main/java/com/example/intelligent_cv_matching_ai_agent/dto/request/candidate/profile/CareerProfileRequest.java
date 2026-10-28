package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
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
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 2)
    BigDecimal expectedSalary,
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

    /** Reject the retired range contract instead of silently discarding the candidate's salary preference. */
    @JsonAnySetter
    public void unknownProperty(
        String name,
        Object value) {
        if (name.equals("expectedSalaryMin") || name.equals("expectedSalaryMax") || name.equals("expected_salary_min")
            || name.equals("expected_salary_max")) {
            throw new IllegalArgumentException("Use one monthly expectedSalary instead of a salary range");
        }
    }
}
