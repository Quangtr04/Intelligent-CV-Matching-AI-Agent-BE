package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record AccountCreateRequest(
    @NotBlank
    @Email
    @Size(max = 255)
    String email,
    @NotBlank
    @Size(max = 255)
    String fullName,
    @NotBlank
    @Pattern(regexp = "CANDIDATE|CAREER_CONSULTANT|STAFF|ADMIN|MANAGER")
    String role,
    @NotBlank
    @Size(min = 10, max = 72)
    String temporaryPassword
) {

    @Override
    public String toString() {
        return "AccountCreateRequest[redacted]";
    }
}
