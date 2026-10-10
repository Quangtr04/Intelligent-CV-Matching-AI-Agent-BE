package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record ImportJobRequest(
    @NotBlank
    @Size(max = 50)
    String sourceSite,
    @NotBlank
    @Size(max = 2000)
    String sourceUrl,
    @NotBlank
    @Size(max = 255)
    String jobTitle,
    @Size(max = 255)
    String companyName,
    @Size(max = 255)
    String location,
    @Size(max = 255)
    String salaryText,
    @NotBlank
    @Size(max = 100)
    String roleCategory,
    @NotBlank
    @Size(max = 100000)
    String content,
    @Size(max = 100)
    List<@Valid JobSkillRequest> skills
) {
}
