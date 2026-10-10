package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record AccountUpdateRequest(
    @NotBlank
    @Size(max = 255)
    String fullName,
    @NotBlank
    @Pattern(regexp = "CANDIDATE|CAREER_CONSULTANT|ADMIN")
    String role,
    @NotBlank
    @Pattern(regexp = "ACTIVE|SUSPENDED")
    String status
) {
}
