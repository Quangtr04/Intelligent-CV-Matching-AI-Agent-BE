package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag;

import com.fasterxml.jackson.annotation.*;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CreateRagDocumentRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @NotBlank
    @Size(max = 100000)
    private String content;

    @Pattern(regexp = "ADMIN")
    private String sourceType;

    private Map<String, Object> metadata;

    private boolean sourceTypePresent, metadataPresent;

    @JsonSetter("title")
    public void setTitle(
        String value) {
        title = value;
    }

    @JsonSetter("content")
    public void setContent(
        String value) {
        content = value;
    }

    @JsonSetter("sourceType")
    public void setSourceType(
        String value) {
        sourceType = value;
        sourceTypePresent = true;
    }

    @JsonSetter("metadata")
    public void setMetadata(
        Map<String, Object> value) {
        metadata = value;
        metadataPresent = true;
    }

    @JsonProperty("title")
    public String title() {
        return title;
    }

    @JsonProperty("content")
    public String content() {
        return content;
    }

    @JsonProperty("sourceType")
    public String sourceType() {
        return sourceType;
    }

    @JsonProperty("metadata")
    public Map<String, Object> metadata() {
        return metadata;
    }

    @AssertTrue(message = "sourceType must be ADMIN and metadata must be an object when supplied")
    @JsonIgnore
    public boolean isOptionalInputValid() {
        return (!sourceTypePresent || sourceType != null) && (!metadataPresent || metadata != null);
    }

    public Map<String, Object> toInputMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", title);
        input.put("content", content);
        if (sourceTypePresent)
            input.put("sourceType", sourceType);
        if (metadataPresent)
            input.put("metadata", metadata);
        return input;
    }
}
