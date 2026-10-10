package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record PersonalProfileRequest(
    @NotBlank
    @Size(max = 255)
    String fullName,
    @Size(max = 2000)
    String avatarUrl,
    @Pattern(regexp = "vi|en")
    String preferredLanguage,
    @Size(max = 255)
    String githubUrl
) {
}
