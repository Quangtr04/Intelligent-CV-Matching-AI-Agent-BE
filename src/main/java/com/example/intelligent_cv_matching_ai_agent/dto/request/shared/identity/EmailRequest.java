package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record EmailRequest(
    @NotBlank
    @Email
    @Size(max = 255)
    String email
) {
}
