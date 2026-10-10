package com.example.intelligent_cv_matching_ai_agent.util.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Component
public class WebhookVerifier {

    private final String secret;

    private final Clock clock;

    @Autowired
    public WebhookVerifier(
        @Value("${app.payment.webhook-secret:}")
        String secret) {
        this(secret, Clock.systemUTC());
    }

    WebhookVerifier(
        String secret,
        Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    public void verify(
        String timestamp,
        String signature,
        String body) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new ApiException(503, "Payment webhook is not configured");

        long time;

        try {
            time = Long.parseLong(timestamp);
        } catch (RuntimeException ex) {
            throw new ApiException(401, "Invalid webhook timestamp");
        }

        if (time < clock.instant().getEpochSecond() - 300 || time > clock.instant().getEpochSecond() + 300)
            throw new ApiException(401, "Webhook timestamp expired");
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}"))
            throw new ApiException(401, "Invalid webhook signature");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature)))
                throw new ApiException(401, "Invalid webhook signature");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
