package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CourseSearchRequest(
    @NotBlank
    @Size(max = 150)
    String skill,
    @NotBlank
    @Pattern(regexp = "vi|en")
    String language,
    @NotBlank
    @Pattern(regexp = "ALL|YOUTUBE|COURSERA")
    String provider
) {
}
