package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.support;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record SupportMessageRequest(
    @NotBlank
    @Size(max = 10000)
    String content
) {
}
