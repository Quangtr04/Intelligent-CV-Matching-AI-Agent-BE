package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record ConsultationOrderRequest(
    @NotNull
    UUID serviceId,
    @Size(max = 255)
    String subject,
    @Size(max = 10000)
    String description
) {
}
