package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing;

import jakarta.validation.constraints.*;

/** Validated input for the billing API. */
public record PaymentReportRequest(
    @NotBlank
    @Size(max = 255)
    String subject,
    @NotBlank
    @Size(max = 10000)
    String description
) {
}
