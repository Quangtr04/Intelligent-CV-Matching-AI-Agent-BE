package com.example.intelligent_cv_matching_ai_agent.util.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.util.shared.billing.WebhookVerifier;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class WebhookVerifierTest {

    private final String secret = "a-production-secret-with-at-least-32-bytes";

    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1800000000), ZoneOffset.UTC);

    private String sign(
        String timestamp,
        String body)
        throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsAuthenticFreshBody() throws Exception {
        String body = "{\"status\":\"COMPLETED\"}";
        String time = "1800000000";
        assertDoesNotThrow(() -> new WebhookVerifier(secret, clock).verify(time, sign(time, body), body));
    }

    @Test
    void rejectsTamperedBody() throws Exception {
        String time = "1800000000";
        String signature = sign(time, "{\"amount\":100}");
        ApiException exception = assertThrows(
            ApiException.class,
            () -> new WebhookVerifier(secret, clock).verify(time, signature, "{\"amount\":1}")
        );
        assertEquals(401, exception.status());
    }

    @Test
    void rejectsValidButExpiredSignature() throws Exception {
        String time = "1799999699";
        String signature = sign(time, "{}");
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new WebhookVerifier(secret, clock).verify(time, signature, "{}"))
                .status()
        );
    }

    @Test
    void unconfiguredWebhookFailsClosed() {
        assertEquals(
            503,
            assertThrows(ApiException.class, () -> new WebhookVerifier("", clock).verify("1800000000", "00", "{}"))
                .status()
        );
    }
}
