package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ConsultationBookingRequest(
    @NotNull
    UUID consultantId,
    @NotNull
    UUID creditOrderId,
    @NotBlank
    @Size(max = 150)
    String targetRole,
    @NotBlank
    @Size(max = 255)
    String subject,
    @NotBlank
    @Size(max = 10000)
    String description,
    @NotNull
    @Future
    OffsetDateTime startsAt,
    Integer durationMinutes
) {
}
