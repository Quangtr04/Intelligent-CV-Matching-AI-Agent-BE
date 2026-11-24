package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JobSkillProficiencyRequest(
    @NotBlank
    @Size(min = 30, max = 50000)
    String jdText
) {
}
