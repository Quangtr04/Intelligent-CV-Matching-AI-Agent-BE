package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpdateAssistantConversationRequest {

    @Size(max = 255)
    private String title;

    @Size(max = 30)
    private String contextType;

    private final Map<String, Object> input = new LinkedHashMap<>();

    @JsonSetter("title")
    public void setTitle(
        String value) {
        title = value;
        input.put("title", value);
    }

    @JsonSetter("context_type")
    public void setContextType(
        String value) {
        contextType = value;
        input.put("context_type", value);
    }

    @JsonSetter("related_resume_id")
    public void setRelatedResumeId(
        UUID value) {
        input.put("related_resume_id", value);
    }

    @JsonSetter("related_job_id")
    public void setRelatedJobId(
        UUID value) {
        input.put("related_job_id", value);
    }

    @AssertTrue(message = "title and context_type must be nonblank strings when supplied")
    @JsonIgnore
    public boolean isOptionalTextValid() {
        return (!input.containsKey("title") || title != null && !title.isBlank())
            && (!input.containsKey("context_type") || contextType != null && !contextType.isBlank());
    }

    public Map<String, Object> toInputMap() {
        return new LinkedHashMap<>(input);
    }
}
