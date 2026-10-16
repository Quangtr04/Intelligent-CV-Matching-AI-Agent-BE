package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConsultationAtsRequest(
    @NotNull
    UUID resumeId
) {
}
