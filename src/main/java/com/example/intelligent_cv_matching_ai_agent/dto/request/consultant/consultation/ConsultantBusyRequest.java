package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

public record ConsultantBusyRequest(
    @NotNull
    OffsetDateTime startsAt,
    @NotNull
    @Future
    OffsetDateTime endsAt,
    @Size(max = 500)
    String description
) {
}
