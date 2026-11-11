package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing;

import jakarta.validation.constraints.Min;

public record ManagerQuotaLimitsRequest(
    @Min(0)
    long maxTokenQuota,
    @Min(0)
    long maxApiQuota
) {
}
