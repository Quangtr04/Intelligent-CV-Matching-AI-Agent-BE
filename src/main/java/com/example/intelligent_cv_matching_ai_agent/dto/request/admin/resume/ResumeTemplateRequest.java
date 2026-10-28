package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.TemplateBooleanDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ResumeTemplateRequest(
    @NotBlank
    @Size(max = 150)
    String name,
    @Size(max = 150)
    String careerPath,
    @NotBlank
    @Pattern(regexp = "vi|en")
    String language,
    @Size(max = 2000)
    String description,
    @NotNull
    @JsonDeserialize(using = TemplateBooleanDeserializer.class)
    Boolean active,
    @NotNull
    @JsonDeserialize(using = TemplateBooleanDeserializer.class)
    Boolean trialEnabled,
    @NotBlank
    @Size(max = 200000)
    String htmlContent
) {
}
