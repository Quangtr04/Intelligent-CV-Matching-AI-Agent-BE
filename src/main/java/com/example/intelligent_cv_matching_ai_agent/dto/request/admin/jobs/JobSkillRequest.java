package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record JobSkillRequest(
    UUID skillId,
    @NotBlank
    @Size(max = 100)
    String name,
    boolean required
) {
}
