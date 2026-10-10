package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.support;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record SupportTicketRequest(
    @NotBlank
    @Size(max = 255)
    String subject,
    @NotBlank
    @Size(max = 50)
    String category,
    @NotBlank
    @Size(max = 10000)
    String description
) {
}
