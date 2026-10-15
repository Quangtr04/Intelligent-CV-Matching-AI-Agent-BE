package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CrawlSourceSettingsRequest(
    @NotBlank
    @Pattern(regexp = "ITVIEC|TOPCV")
    String sourceSite,
    @NotBlank
    @Size(max = 255)
    String keyword,
    @NotBlank
    @Size(max = 100)
    String roleCategory,
    @Size(max = 120)
    String location,
    @Min(1)
    @Max(20)
    Integer limit,
    @Min(1)
    @Max(5)
    Integer maxPages
) {
}
