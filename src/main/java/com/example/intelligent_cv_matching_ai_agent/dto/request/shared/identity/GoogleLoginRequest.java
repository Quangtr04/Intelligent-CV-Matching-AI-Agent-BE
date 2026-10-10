package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record GoogleLoginRequest(
    @NotBlank
    @Size(max = 10000)
    String idToken
) {

    @Override
    public String toString() {
        return "GoogleLoginRequest[redacted]";
    }
}
