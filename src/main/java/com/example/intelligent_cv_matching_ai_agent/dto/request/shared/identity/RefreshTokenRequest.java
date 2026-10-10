package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.*;

/** Validated input for the identity API. */
public record RefreshTokenRequest(
    @NotBlank
    @Size(max = 120)
    String refreshToken
) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[redacted]";
    }
}
