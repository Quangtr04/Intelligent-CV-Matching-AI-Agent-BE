package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record CreateConsultationRequest(
    @NotBlank
    @Size(max = 255)
    String subject,
    @Size(max = 50)
    String requestType,
    @NotBlank
    @Size(max = 10000)
    String description
) {
}
