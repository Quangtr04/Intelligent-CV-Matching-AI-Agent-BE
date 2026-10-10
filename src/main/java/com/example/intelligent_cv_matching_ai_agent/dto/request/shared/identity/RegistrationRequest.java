package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record RegistrationRequest(
    @NotBlank
    @Email
    @Size(max = 255)
    String email,
    @NotBlank
    @Size(min = 10, max = 72)
    String password,
    @NotBlank
    @Size(max = 255)
    String fullName,
    @Pattern(regexp = "vi|en")
    String preferredLanguage
) {

    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
