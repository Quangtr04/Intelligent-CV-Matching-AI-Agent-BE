package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record PaymentReportResolutionRequest(
    @NotBlank
    @Size(max = 10000)
    String resolution
) {
}
