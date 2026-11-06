package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation;

import jakarta.validation.constraints.*;

public record ConsultationRatingRequest(
    @NotNull
    @Min(1)
    @Max(5)
    Integer rating,
    @NotBlank
    @Size(max = 2000)
    String comment
) {
}
