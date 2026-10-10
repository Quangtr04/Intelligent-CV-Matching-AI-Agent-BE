package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record PasswordChangeRequest(
    @NotBlank
    @Size(max = 72)
    String currentPassword,
    @NotBlank
    @Size(min = 10, max = 72)
    String newPassword
) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[redacted]";
    }
}
