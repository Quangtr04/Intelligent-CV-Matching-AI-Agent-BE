package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** Validated input for the billing API. */
public record PaymentOrderRequest(
    @NotNull
    UUID tierId,
    @Min(1)
    @Max(12)
    Integer durationMonths
) {
}
