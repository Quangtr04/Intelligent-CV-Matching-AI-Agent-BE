package com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CrawlSettingsResponse(
    boolean enabled,
    String fixedDelay,
    UUID actorId,
    List<CrawlSourceSettingsResponse> sources,
    String configurationSource,
    long version,
    UUID updatedBy,
    Instant updatedAt
) {

    public CrawlSettingsResponse {
        sources = List.copyOf(sources);
    }
}
