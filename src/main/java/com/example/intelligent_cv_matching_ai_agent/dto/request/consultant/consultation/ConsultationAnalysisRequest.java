package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

public record ConsultationAnalysisRequest(
    UUID resumeId,
    UUID jobId,
    @Min(1)
    @Max(100)
    @Schema(defaultValue = "20")
    Integer limit,
    @Min(0)
    @Schema(defaultValue = "0")
    Integer offset
) {

    public ConsultationAnalysisRequest {
        if (limit == null) {
            limit = 20;
        }

        if (offset == null) {
            offset = 0;
        }
    }
}
