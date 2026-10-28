package com.example.intelligent_cv_matching_ai_agent.util.shared.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs the documented one-time checkout fields before HTML/form URL encoding. */
public final class SePayCheckoutSigner {

    private static final List<String> SIGNED_FIELDS = List.of(
        "order_amount",
        "merchant",
        "currency",
        "operation",
        "order_description",
        "order_invoice_number",
        "customer_id",
        "payment_method",
        "success_url",
        "error_url",
        "cancel_url"
    );

    private SePayCheckoutSigner() {
    }

    public static String sign(
        Map<String, String> fields,
        String secret) {
        if (fields == null) {
            throw new IllegalArgumentException("Checkout fields are required");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("SePay merchant secret is required");
        }
        StringJoiner signed = new StringJoiner(",");
        for (String field : SIGNED_FIELDS) {
            if (fields.containsKey(field)) {
                signed.add(field + "=" + Objects.toString(fields.get(field), ""));
            }
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = hmac.doFinal(signed.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign SePay checkout fields", ex);
        }
    }
}
