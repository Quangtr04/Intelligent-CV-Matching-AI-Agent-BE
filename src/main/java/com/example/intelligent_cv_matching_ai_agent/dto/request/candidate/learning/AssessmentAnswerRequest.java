package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record AssessmentAnswerRequest(
    @NotNull
    UUID questionId,
    @NotBlank
    @Pattern(regexp = "[ABCD]")
    String selectedOption
) {
}
