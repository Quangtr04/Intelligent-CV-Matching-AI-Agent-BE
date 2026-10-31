package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** A cover letter or application email written by the candidate, saved without AI generation. */
public record ApplicationDraftCreateRequest(
    @NotNull
    UUID jobId,
    UUID resumeId,
    @NotNull
    @Pattern(regexp = "APPLICATION_EMAIL|COVER_LETTER")
    String draftType,
    @NotBlank
    @Size(max = 30000)
    String content
) {
}
