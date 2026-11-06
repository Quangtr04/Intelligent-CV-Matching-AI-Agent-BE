package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record SubscriptionQuotaRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z0-9_]{2,100}")
    String featureCode,
    @Min(0)
    long quotaValue,
    @NotBlank
    @Pattern(regexp = "DAILY|MONTHLY|YEARLY|LIFETIME|SUBSCRIPTION")
    String quotaPeriod
) {
}
