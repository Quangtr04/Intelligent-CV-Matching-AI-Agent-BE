package com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/** A trial accepts either an approved demo or editable personal facts, never a private CV ID. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuestBuildRequest(
    @NotNull
    @JsonProperty("template_id")
    UUID templateId,
    @JsonProperty("demo_resume_id")
    UUID demoResumeId,
    @Size(max = 30)
    @JsonProperty("structured_data")
    Map<String, Object> structuredData,
    @Size(max = 200000)
    @JsonProperty("raw_content")
    String rawContent,
    @Pattern(regexp = "vi|en|VI|EN")
    String language,
    @JsonProperty("use_ai")
    Boolean useAi
) {

    public GuestBuildRequest(
        UUID templateId,
        UUID demoResumeId) {
        this(templateId, demoResumeId, null, null, null, null);
    }

    @AssertTrue(message = "Supply either demo_resume_id or structured_data; raw_content belongs to personal mode only")
    @JsonIgnore
    public boolean isValidSource() {
        return demoResumeId == null ? structuredData != null : structuredData == null && rawContent == null;
    }

    @Override
    public String toString() {
        return "GuestBuildRequest[templateId=" + templateId + ", demoResumeId=" + demoResumeId
            + ", personalFacts=[REDACTED], language=" + language + ", useAi=" + useAi + "]";
    }
}
