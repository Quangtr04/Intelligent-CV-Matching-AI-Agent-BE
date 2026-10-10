package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record MatchRequest(
    @NotBlank
    String basis,
    List<UUID> resumeIds,
    @Min(1)
    @Max(100)
    int limit
) {
}
