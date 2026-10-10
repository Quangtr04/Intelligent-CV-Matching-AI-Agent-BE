package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing;

import java.math.BigDecimal;
import java.util.UUID;

/** Provider event parsed only after its HMAC signature has been verified. */
public record PaymentWebhookRequest(
    UUID orderId,
    String providerOrderId,
    String eventId,
    String status,
    BigDecimal amount,
    String currency
) {
}
