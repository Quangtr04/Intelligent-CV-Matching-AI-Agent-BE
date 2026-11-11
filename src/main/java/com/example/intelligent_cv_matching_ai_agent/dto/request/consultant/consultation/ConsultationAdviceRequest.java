package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record ConsultationAdviceRequest(
    @NotNull
    @Min(0)
    @Max(Long.MAX_VALUE - 1)
    Long expectedVersion,
    @NotNull
    @Pattern(regexp = "DRAFT|PUBLISHED")
    String status,
    UUID resumeId,
    UUID jobId,
    @NotNull
    @Size(max = 7)
    Map<String, Object> advice
) {
}
