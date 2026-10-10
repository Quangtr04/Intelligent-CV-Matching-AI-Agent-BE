package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.ai;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AssistantMessageRequest {

    @NotBlank
    @Size(max = 8000)
    private String message;

    private Boolean retrieve;

    private boolean retrievePresent;

    @JsonSetter("message")
    public void setMessage(
        String value) {
        message = value;
    }

    @JsonSetter("retrieve")
    public void setRetrieve(
        Boolean value) {
        retrieve = value;
        retrievePresent = true;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("retrieve")
    public Boolean retrieve() {
        return retrieve;
    }

    @AssertTrue(message = "retrieve must be boolean")
    @JsonIgnore
    public boolean isRetrieveValid() {
        return !retrievePresent || retrieve != null;
    }

    public Map<String, Object> toInputMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        if (retrievePresent)
            input.put("retrieve", retrieve);
        return input;
    }
}
