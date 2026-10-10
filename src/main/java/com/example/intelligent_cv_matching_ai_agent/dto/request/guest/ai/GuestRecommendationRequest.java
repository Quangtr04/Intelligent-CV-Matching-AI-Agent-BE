package com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GuestRecommendationRequest(
    @NotNull
    @JsonProperty("demo_resume_id")
    UUID demoResumeId
) {
}
