package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Validated input for configuring a MANAGEMENT user's internal profile, sub-role, and permissions. */
public record ManagementProfileRequest(
    @NotBlank
    @Pattern(regexp = "MANAGER|STAFF")
    String subRole,
    @Size(max = 20)
    List<@Pattern(regexp = "JOB_ENTRY|JOB_SOURCING|JOB_APPROVAL|TASK_ASSIGNMENT|STAFF_MANAGEMENT") String> permissions,
    UUID managerUserId,
    @Size(max = 120)
    String department,
    @Size(max = 2000)
    String notes
) {
}
