package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** Validated input for the admin API. */
public record AnnouncementRequest(
    @NotBlank
    @Size(max = 255)
    String title,
    @NotBlank
    @Size(max = 50000)
    String content,
    @Future
    OffsetDateTime expiresAt,
    @NotEmpty
    @Size(max = 100)
    List<@Valid AnnouncementTargetRequest> targets
) {
}
