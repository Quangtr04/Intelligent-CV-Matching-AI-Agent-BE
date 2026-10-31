package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** Text the candidate wrote and wants the AI to check against one job (and optionally one CV). */
public record ApplicationDraftReviewRequest(
    @NotNull
    UUID jobId,
    UUID resumeId,
    @NotNull
    @Pattern(regexp = "APPLICATION_EMAIL|COVER_LETTER")
    String draftType,
    @Size(max = 300)
    String subject,
    @NotBlank
    @Size(max = 12000)
    String content,
    @Pattern(regexp = "vi|en")
    String language
) {
}
