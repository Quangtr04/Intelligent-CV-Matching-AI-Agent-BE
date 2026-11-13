package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning;

import jakarta.validation.constraints.*;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record AssessmentRequest(
    @Size(max = 255)
    String targetJobTitle,
    UUID jobId,
    UUID resumeId,
    @Size(min = 30, max = 50000)
    String jdText
) {

    public AssessmentRequest(
        String targetJobTitle,
        UUID jobId) {
        this(targetJobTitle, jobId, null, null);
    }

    @AssertTrue(message = "Select one stored JD or paste a JD with a target job title; CV is optional")
    @JsonIgnore
    public boolean isContextValid() {
        boolean pasted = jdText != null && !jdText.isBlank();
        return (jobId != null) != pasted && (!pasted || (targetJobTitle != null && !targetJobTitle.isBlank()));
    }

    @Override
    public String toString() {
        return "AssessmentRequest[targetJobTitle=" + targetJobTitle + ", jobId=" + jobId + ", resumeId=" + resumeId
            + ", jdText=[REDACTED]]";
    }
}
