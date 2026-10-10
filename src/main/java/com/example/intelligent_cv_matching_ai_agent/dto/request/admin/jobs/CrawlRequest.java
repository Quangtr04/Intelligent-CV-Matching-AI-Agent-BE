package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.constraints.*;
import java.util.List;

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
    @NotEmpty
    @Size(max = 20)
    // @formatter:off
    List<
        @NotBlank
        @Size(max = 2000)
        String
    > urls
    // @formatter:on
) {
}
