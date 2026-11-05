package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** Validated input for the admin API. */
public record AnnouncementTargetRequest(
    @NotBlank
    @Pattern(regexp = "ALL|ROLE|USER")
    String type,
    @Pattern(regexp = "CANDIDATE|CAREER_CONSULTANT|ADMIN|MANAGEMENT|STAFF")
    String role,
    UUID userId
) {
}
