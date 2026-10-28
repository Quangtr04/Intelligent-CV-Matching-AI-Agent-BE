package com.example.intelligent_cv_matching_ai_agent.dto.response.shared.billing;

import java.util.List;
import java.util.Map;

/** Ordered form fields keep the browser submission aligned with the checkout signature. */
public record SePayCheckoutResponse(
    Map<String, Object> order,
    String checkoutUrl,
    String method,
    List<FormField> formFields,
    String environment
) {

    public record FormField(
        String name,
        String value
    ) {
    }
}
