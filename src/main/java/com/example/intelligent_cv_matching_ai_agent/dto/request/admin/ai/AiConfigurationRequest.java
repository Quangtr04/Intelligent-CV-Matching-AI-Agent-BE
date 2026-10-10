package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai;

import jakarta.validation.constraints.*;
import java.util.Map;

/** Validated input for the admin API. */
public record AiConfigurationRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z_]{2,50}")
    String configType,
    @NotBlank
    @Size(max = 100)
    String provider,
    @NotBlank
    @Size(max = 150)
    String modelName,
    @NotNull
    Map<String, Object> configuration
) {
}
