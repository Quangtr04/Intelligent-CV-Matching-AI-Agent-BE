package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank
    @Size(max = 72)
    String newPassword,
    @NotBlank
    @Size(min = 10, max = 72)
    String newPasswordConfirmation,
    @NotBlank
    @Size(max = 255)
    String token
) {
    
}
