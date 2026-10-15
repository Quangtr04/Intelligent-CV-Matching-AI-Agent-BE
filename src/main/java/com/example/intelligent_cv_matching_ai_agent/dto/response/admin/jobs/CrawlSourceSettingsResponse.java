package com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs;

public record CrawlSourceSettingsResponse(
    String sourceSite,
    String keyword,
    String roleCategory,
    String location,
    int limit,
    int maxPages
) {
}
