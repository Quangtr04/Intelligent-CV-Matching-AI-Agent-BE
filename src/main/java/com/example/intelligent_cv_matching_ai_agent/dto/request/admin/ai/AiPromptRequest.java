package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai;

import jakarta.validation.constraints.*;

/** Validated input for the admin API. */
public record AiPromptRequest(
    @NotBlank
    @Pattern(regexp = "[A-Z0-9_]{2,100}")
    String featureCode,
    @NotBlank
    @Size(max = 150)
    String name,
    @NotBlank
    @Size(max = 50000)
    String systemPrompt,
    @NotBlank
    @Size(max = 50000)
    String userPromptTemplate
) {
}
