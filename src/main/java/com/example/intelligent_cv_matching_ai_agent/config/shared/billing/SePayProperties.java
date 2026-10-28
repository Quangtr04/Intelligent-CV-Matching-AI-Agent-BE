package com.example.intelligent_cv_matching_ai_agent.config.shared.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.payment.sepay")
public class SePayProperties {

    private boolean enabled;

    private String environment = "sandbox";

    private String merchantId = "";

    private String merchantSecret = "";

    private String ipnSecret = "";

    private String returnUrl = "";

    private String paymentMethod = "BANK_TRANSFER";
}
