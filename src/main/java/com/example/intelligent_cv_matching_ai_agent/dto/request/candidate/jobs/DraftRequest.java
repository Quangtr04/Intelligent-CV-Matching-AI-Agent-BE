package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record DraftRequest(
    @NotNull
    UUID resumeId,
    @Pattern(regexp = "vi|en")
    String language
) {
}
