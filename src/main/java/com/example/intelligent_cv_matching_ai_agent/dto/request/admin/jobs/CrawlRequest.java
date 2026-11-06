package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record CrawlRequest(
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
    @Size(max = 100)
    // @formatter:off
    List<
        @NotBlank
        @Size(max = 2000)
        String
    > urls,
    // @formatter:on
    @Min(1)
    @Max(100)
    Integer limit,
    @Min(1)
    @Max(10)
    Integer maxPages,
    UUID taskId
) {

    public CrawlRequest(
        String sourceSite,
        String keyword,
        String roleCategory,
        String location,
        List<String> urls,
        Integer limit,
        Integer maxPages) {
        this(sourceSite, keyword, roleCategory, location, urls, limit, maxPages, null);
    }

    public CrawlRequest(
        String sourceSite,
        String keyword,
        String roleCategory,
        String location,
        List<String> urls) {
        this(sourceSite, keyword, roleCategory, location, urls, 50, 5, null);
    }

    public int effectiveLimit() {
        return limit == null ? 50 : limit;
    }

    public int effectiveMaxPages() {
        return maxPages == null ? 5 : maxPages;
    }
}
