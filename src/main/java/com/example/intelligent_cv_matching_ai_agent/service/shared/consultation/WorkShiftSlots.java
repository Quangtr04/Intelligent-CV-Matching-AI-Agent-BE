package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import java.time.*;
import java.util.*;

/** Grid is anchored to shift start, never to remaining availability or booking state. */
public final class WorkShiftSlots {

    private WorkShiftSlots() {
    }

    public record Slot(
        OffsetDateTime startsAt,
        OffsetDateTime endsAt
    ) {
    }

    public static OffsetDateTime time(
        Object value) {
        if (value instanceof OffsetDateTime t) {
            return t;
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(value.toString());
    }

    public static List<Slot> generate(
        Map<String, Object> shift,
        int minutes) {
        if (minutes != 30 && minutes != 60) {
            throw new IllegalArgumentException("Unsupported consultation duration");
        }
        List<Slot> result = new ArrayList<>();
        OffsetDateTime end = time(shift.get("ends_at"));
        for (OffsetDateTime start = time(shift.get("starts_at")); !start.plusMinutes(minutes).isAfter(end); start =
            start.plusMinutes(minutes + 15)) {
            OffsetDateTime stop = start.plusMinutes(minutes);
            if (shift.get("lunch_starts_at") == null || !start.isBefore(time(shift.get("lunch_ends_at")))
                || !stop.isAfter(time(shift.get("lunch_starts_at")))) {
                result.add(new Slot(start, stop));
            }
        }
        return result;
    }

    public static boolean overlaps(
        OffsetDateTime start,
        OffsetDateTime end,
        Map<String, Object> interval) {
        return start.isBefore(time(interval.get("ends_at"))) && end.isAfter(time(interval.get("starts_at")));
    }
}
