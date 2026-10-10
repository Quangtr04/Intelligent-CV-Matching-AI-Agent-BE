package com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A trial uses server-owned demo facts and an approved template only. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuestBuildRequest(
    @NotNull
    @JsonProperty("template_id")
    UUID templateId,
    @NotNull
    @JsonProperty("demo_resume_id")
    UUID demoResumeId
) {
}
