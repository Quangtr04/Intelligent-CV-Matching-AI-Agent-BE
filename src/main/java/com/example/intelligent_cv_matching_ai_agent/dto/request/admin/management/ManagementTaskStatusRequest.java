package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management;

import jakarta.validation.constraints.*;

/** Validated input for updating the status of a management task assignment. */
public record ManagementTaskStatusRequest(
    @NotBlank
    @Pattern(regexp = "TODO|IN_PROGRESS|DONE|CANCELLED")
    String status
) {
}
