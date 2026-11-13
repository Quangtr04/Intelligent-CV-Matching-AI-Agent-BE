package com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation;

import java.time.*;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record WorkShiftRequest(
    @NotNull
    UUID consultantId,
    @NotNull
    LocalDate fromDate,
    @NotNull
    LocalDate toDate,
    @NotNull
    LocalTime startsAt,
    @NotNull
    LocalTime endsAt,
    LocalTime lunchStartsAt,
    LocalTime lunchEndsAt,
    Set<DayOfWeek> weekdays
) {
}
