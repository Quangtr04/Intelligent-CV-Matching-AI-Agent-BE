
package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AISupportRequest(

    @NotEmpty(message = "Question must not be empty")
    @Size(max = 8)
    @JsonProperty("question")
    // @formatter:off
    List<
        @NotBlank
        @Size(max = 4000)
        String
    > question,
    // @formatter:on

    @NotNull
    @JsonProperty("is_urgent")
    Boolean urgent,

    @JsonAlias("resume_id")
    UUID resumeId,

    @Pattern(regexp = "vi|en")
    String language,

    @Pattern(regexp = "summary|experience|project")
    String field,

    @Size(max = 8000)
    String currentText,

    @Size(max = 255)
    String position,

    @Size(max = 30)
    // @formatter:off
    List<
        @NotBlank
        @Size(max = 100)
        String
    > skills,
    // @formatter:on

    @Size(max = 255)
    String title,

    @Size(max = 255)
    String subtitle,

    @Size(max = 8000)
    String background

) {

    public AISupportRequest(
        List<String> question,
        Boolean urgent) {
        this(question, urgent, null, null, null, null, null, null, null, null, null);
    }
}
