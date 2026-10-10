package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record MatchingFeedbackRequest(
    @NotBlank
    @Pattern(regexp = "LIKE|DISLIKE")
    String feedbackType,
    @Min(1)
    @Max(5)
    Integer rating,
    @Size(max = 2000)
    String comment
) {
}
