package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.support;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** Validated input for the admin API. */
public record SupportTicketUpdateRequest(
    @NotBlank
    @Pattern(regexp = "OPEN|IN_PROGRESS|RESOLVED|CLOSED")
    String status,
    @NotBlank
    @Pattern(regexp = "LOW|NORMAL|HIGH|URGENT")
    String priority,
    UUID assignedAdminId
) {
}
