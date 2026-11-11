package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConsultationPrivateNotesRequest(
    @NotNull
    @Min(0)
    @Max(Long.MAX_VALUE - 1)
    Long expectedVersion,
    @NotNull
    @Size(max = 10000)
    String notes,
    @Valid
    Rubric rubric
) {

    public record Rubric(
        @NotNull
        @Min(0)
        @Max(10)
        Integer cvQuality,
        @NotNull
        @Min(0)
        @Max(10)
        Integer technicalDepth,
        @NotNull
        @Min(0)
        @Max(10)
        Integer communication,
        @NotNull
        @Min(0)
        @Max(10)
        Integer marketReadiness
    ) {
    }
}
