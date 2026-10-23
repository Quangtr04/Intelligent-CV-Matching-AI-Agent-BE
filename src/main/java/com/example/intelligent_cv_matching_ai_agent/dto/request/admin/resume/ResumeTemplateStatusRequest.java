package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.TemplateBooleanDeserializer;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ResumeTemplateStatusRequest(
    @NotNull
    @JsonDeserialize(using = TemplateBooleanDeserializer.class)
    Boolean active
) {
}
