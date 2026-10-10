package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Validated input for the admin API. */
public record SubscriptionTierRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z0-9_]{2,30}")
    String tierCode,
    @NotBlank
    @Size(max = 100)
    String displayName,
    @NotNull
    @DecimalMin("0")
    @DecimalMax("99999999.99")
    @Digits(integer = 8, fraction = 2)
    BigDecimal priceMonthly,
    @NotBlank
    @Pattern(regexp = "[A-Z]{3}")
    String currency,
    boolean active
) {
}
