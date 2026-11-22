package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ResumeTemplatePackagesRequest(
    @NotEmpty
    @Size(max = 50)
    List<String> allowedTierCodes
) {
}
