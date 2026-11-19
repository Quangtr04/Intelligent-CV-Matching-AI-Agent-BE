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
    boolean active,
    String tierType,
    @Min(0)
    @Max(1000000000)
    long tokenAmount
) {

    public SubscriptionTierRequest(
        String tierCode,
        String displayName,
        BigDecimal priceMonthly,
        String currency,
        boolean active) {
        this(tierCode, displayName, priceMonthly, currency, active, "SUBSCRIPTION", 0);
    }

    public String effectiveTierType() {
        return tierType == null || tierType.isBlank()
            ? "SUBSCRIPTION"
            : tierType.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
