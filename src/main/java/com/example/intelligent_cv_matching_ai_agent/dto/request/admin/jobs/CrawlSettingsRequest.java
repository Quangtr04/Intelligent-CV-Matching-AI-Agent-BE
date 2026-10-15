package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CrawlSettingsRequest(
    @NotNull
    Boolean enabled,
    @NotBlank
    @Size(max = 100)
    String fixedDelay,
    UUID actorId,
    @Valid
    @NotNull
    @Size(max = 20)
    List<CrawlSourceSettingsRequest> sources
) {
}
