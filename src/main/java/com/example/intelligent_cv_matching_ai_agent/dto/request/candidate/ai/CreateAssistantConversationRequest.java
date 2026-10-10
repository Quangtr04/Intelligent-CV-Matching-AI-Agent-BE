package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CreateAssistantConversationRequest {

    @Size(max = 255)
    private String title;

    @Size(max = 50)
    private String contextType;

    private UUID relatedJobId, relatedResumeId, relatedGapAnalysisId, relatedRoadmapId;

    private boolean titlePresent, contextTypePresent;

    @JsonSetter("title")
    public void setTitle(
        String value) {
        title = value;
        titlePresent = true;
    }

    @JsonSetter("context_type")
    public void setContextType(
        String value) {
        contextType = value;
        contextTypePresent = true;
    }

    @JsonSetter("related_job_id")
    public void setRelatedJobId(
        UUID value) {
        relatedJobId = value;
    }

    @JsonSetter("related_resume_id")
    public void setRelatedResumeId(
        UUID value) {
        relatedResumeId = value;
    }

    @JsonSetter("related_gap_analysis_id")
    public void setRelatedGapAnalysisId(
        UUID value) {
        relatedGapAnalysisId = value;
    }

    @JsonSetter("related_roadmap_id")
    public void setRelatedRoadmapId(
        UUID value) {
        relatedRoadmapId = value;
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }

    @JsonProperty("context_type")
    public String contextType() {
        return contextType;
    }

    @JsonProperty("related_job_id")
    public UUID relatedJobId() {
        return relatedJobId;
    }

    @JsonProperty("related_resume_id")
    public UUID relatedResumeId() {
        return relatedResumeId;
    }

    @JsonProperty("related_gap_analysis_id")
    public UUID relatedGapAnalysisId() {
        return relatedGapAnalysisId;
    }

    @JsonProperty("related_roadmap_id")
    public UUID relatedRoadmapId() {
        return relatedRoadmapId;
    }

    @AssertTrue(message = "title and context_type must be strings when supplied")
    @JsonIgnore
    public boolean isOptionalTextValid() {
        return (!titlePresent || title != null) && (!contextTypePresent || contextType != null);
    }

    public Map<String, Object> toInputMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        if (titlePresent)
            input.put("title", title);
        if (contextTypePresent)
            input.put("context_type", contextType);
        put(input, "related_job_id", relatedJobId);
        put(input, "related_resume_id", relatedResumeId);
        put(input, "related_gap_analysis_id", relatedGapAnalysisId);
        put(input, "related_roadmap_id", relatedRoadmapId);
        return input;
    }

    private static void put(
        Map<String, Object> input,
        String key,
        Object value) {
        if (value != null)
            input.put(key, value);
    }
}
