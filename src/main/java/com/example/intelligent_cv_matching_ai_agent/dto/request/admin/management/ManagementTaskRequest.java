package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Validated input for creating or updating a management task assignment. */
public record ManagementTaskRequest(
    @NotBlank
    @Size(max = 255)
    String title,
    @Size(max = 4000)
    String description,
    @NotBlank
    @Pattern(regexp = "JOB_ENTRY|JOB_SOURCING|JOB_REVIEW|GENERAL")
    String taskType,
    @NotNull
    UUID assignedToUserId,
    @Size(max = 100)
    String roleCategory,
    @Size(max = 50)
    String sourceSite,
    @Size(max = 255)
    String keyword,
    @Size(max = 120)
    String targetLocation,
    @Min(1)
    @Max(1000)
    Integer targetCount,
    @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT")
    String priority,
    OffsetDateTime dueAt
) {
}
