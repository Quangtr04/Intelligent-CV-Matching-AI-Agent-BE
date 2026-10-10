package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record VerifyEmailRequest(
    @NotBlank
    @Email
    String email,
    @NotBlank
    @Pattern(regexp = "[0-9]{6}")
    String code
) {

    @Override
    public String toString() {
        return "VerifyEmailRequest[redacted]";
    }
}
