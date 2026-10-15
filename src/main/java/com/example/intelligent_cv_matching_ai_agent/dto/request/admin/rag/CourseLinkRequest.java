package com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CourseLinkRequest(
    @NotBlank
    @Size(max = 255)
    String title,
    @NotBlank
    @Size(max = 2048)
    String url,
    @NotBlank
    @Size(max = 12000)
    String description,
    @NotNull
    @Size(min = 1, max = 30)
    List<String> skills,
    @NotBlank
    @Pattern(regexp = "[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,6})?")
    @Size(max = 10)
    String language,
    @NotNull
    Boolean isFree
) {
}
