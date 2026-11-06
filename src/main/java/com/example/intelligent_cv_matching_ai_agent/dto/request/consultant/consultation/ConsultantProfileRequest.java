package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import jakarta.validation.constraints.*;
import java.util.List;

public record ConsultantProfileRequest(
    @NotNull
    @Size(min = 1, max = 20)
    List<String> targetRoles,
    @NotNull
    @Size(max = 3000)
    String bio
) {
}
