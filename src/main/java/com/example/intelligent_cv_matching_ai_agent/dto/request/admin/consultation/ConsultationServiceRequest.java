package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ConsultationServiceRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z0-9_]{2,50}")
    String serviceCode,
    @NotBlank
    @Size(max = 150)
    String displayName,
    @NotNull
    @Size(max = 5000)
    String description,
    @NotBlank
    @Pattern(regexp = "ASYNC|LIVE")
    String deliveryMode,
    @Min(1)
    @Max(480)
    Integer durationMinutes,
    @NotNull
    @DecimalMin("1")
    @Digits(integer = 8, fraction = 0)
    BigDecimal price,
    boolean isActive,
    @Min(1)
    @Max(100)
    Integer sessionsCount,
    Boolean isSessionPack
) {

    public ConsultationServiceRequest(
        String serviceCode,
        String displayName,
        String description,
        String deliveryMode,
        Integer durationMinutes,
        BigDecimal price,
        boolean isActive) {
        this(
            serviceCode,
            displayName,
            description,
            deliveryMode,
            durationMinutes,
            price,
            isActive,
            1,
            "LIVE".equals(deliveryMode)
        );
    }
}
