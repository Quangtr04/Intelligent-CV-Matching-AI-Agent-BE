package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

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
    List<@Valid JobSkillRequest> skills,
    @Size(max = 50)
    String employmentType,
    @Size(max = 100000)
    String requirements,
    @Size(max = 100000)
    String responsibilities,
    @Size(max = 100000)
    String benefits,
    OffsetDateTime postedAt,
    OffsetDateTime validThrough
) {

    public ImportJobRequest(
        String sourceSite,
        String sourceUrl,
        String jobTitle,
        String companyName,
        String location,
        String salaryText,
        String roleCategory,
        String content,
        List<JobSkillRequest> skills) {
        this(
            sourceSite,
            sourceUrl,
            jobTitle,
            companyName,
            location,
            salaryText,
            roleCategory,
            content,
            skills,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
