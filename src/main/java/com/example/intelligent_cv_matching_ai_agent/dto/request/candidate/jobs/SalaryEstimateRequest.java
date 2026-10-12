package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SalaryEstimateRequest(
    @NotBlank
    @Size(max = 160)
    String targetJobTitle,
    @Size(max = 120)
    String location,
    @Size(max = 80)
    String currency,
    @Pattern(regexp = "vi|en")
    String language
) {
}
