package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.WorkShiftRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Service
public class WorkShiftService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SqlStore db;

    private final ConsultationBookingRepository bookings;

    private final CurrentUser user;

    public WorkShiftService(
        SqlStore db,
        ConsultationBookingRepository bookings,
        CurrentUser user) {
        this.db = db;
        this.bookings = bookings;
        this.user = user;
    }

    public List<Map<String, Object>> list(
        UUID consultant,
        OffsetDateTime from,
        OffsetDateTime to) {
        if (user.hasOperationsRole()) {
            user.requireOperationsRole();
        } else {
            user.requireRole("CAREER_CONSULTANT");
            consultant = user.id();
        }
        range(from, to);
        return db.list(
            "SELECT * FROM consultant_work_shifts WHERE consultant_user_id=? AND starts_at<? "
                + "AND ends_at>? ORDER BY starts_at",
            consultant,
            to,
            from
        );
    }

    public List<Map<String, Object>> slots(
        UUID consultant,
        OffsetDateTime from,
        OffsetDateTime to,
        int minutes) {
        user.id();
        range(from, to);
        bookings.consultant(consultant);
        if (minutes != 30 && minutes != 60) {
            throw ApiException.badRequest("Consultations must last 30 or 60 minutes");
        }
        var busy = bookings.availability(consultant, from.minusMinutes(15), to.plusMinutes(15));
        var candidate = bookings.candidateIntervals(user.id(), from, to);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var shift : db.list(
            "SELECT * FROM consultant_work_shifts WHERE consultant_user_id=? "
                + "AND cancelled_at IS NULL AND starts_at<? AND ends_at>? ORDER BY starts_at",
            consultant,
            to,
            from
        )) {
            for (var slot : WorkShiftSlots.generate(shift, minutes)) {
                if (slot.startsAt().isBefore(from) || slot.endsAt().isAfter(to)
                    || !slot.startsAt().isAfter(OffsetDateTime.now())) {
                    continue;
                }
                if (busy.stream().anyMatch(
                    b -> WorkShiftSlots.overlaps(slot.startsAt().minusMinutes(15), slot.endsAt().plusMinutes(15), b)
                ) || candidate.stream().anyMatch(b -> WorkShiftSlots.overlaps(slot.startsAt(), slot.endsAt(), b))) {
                    continue;
                }
                result.add(fields("starts_at", slot.startsAt(), "ends_at", slot.endsAt()));
            }
        }
        return result;
    }

    @Transactional
    public List<Map<String, Object>> save(
        UUID id,
        WorkShiftRequest input) {
        user.requireOperationsRole();
        if (input.fromDate() == null || input.toDate() == null || input.startsAt() == null || input.endsAt() == null
            || input.consultantId() == null || input.toDate().isBefore(input.fromDate())
            || input.toDate().isAfter(input.fromDate().plusDays(92)) || !input.endsAt().isAfter(input.startsAt())) {
            throw ApiException.badRequest("Select a valid shift and a date range of at most 93 days");
        }
        if ((input.lunchStartsAt() == null) != (input.lunchEndsAt() == null) || (input.lunchStartsAt() != null
            && (input.lunchStartsAt().isBefore(input.startsAt()) || input.lunchEndsAt().isAfter(input.endsAt())
                || !input.lunchEndsAt().isAfter(input.lunchStartsAt())))) {
            throw ApiException.badRequest("Lunch must be inside the shift");
        }
        bookings.lockConsultant(input.consultantId());
        if (id != null) {
            var old = db.one(
                "SELECT * FROM consultant_work_shifts WHERE id=? AND consultant_user_id=? "
                    + "AND cancelled_at IS NULL",
                id,
                input.consultantId()
            );
            if (!input.fromDate().equals(input.toDate())) {
                throw ApiException.badRequest("Edit one occurrence at a time");
            }
            protect(old);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        UUID series = UUID.randomUUID();
        for (LocalDate day = input.fromDate(); !day.isAfter(input.toDate()); day = day.plusDays(1)) {
            if (id == null && input.weekdays() != null && !input.weekdays().isEmpty()
                && !input.weekdays().contains(day.getDayOfWeek())) {
                continue;
            }
            var start = day.atTime(input.startsAt()).atZone(ZONE).toOffsetDateTime();
            var end = day.atTime(input.endsAt()).atZone(ZONE).toOffsetDateTime();
            if (!start.isAfter(OffsetDateTime.now())) {
                throw ApiException.badRequest("Shift must start in the future");
            }
            if (db.optional(
                "SELECT id FROM consultant_work_shifts WHERE consultant_user_id=? AND cancelled_at IS NULL "
                    + "AND starts_at<? AND ends_at>? AND id<>?",
                input.consultantId(),
                end,
                start,
                id == null ? new UUID(0, 0) : id
            ).isPresent()) {
                throw ApiException.conflict("Shifts overlap");
            }
            if (bookings.busyOverlapsBooking(input.consultantId(), start, end)) {
                throw ApiException.conflict("Shift overlaps an existing booking; preserve the current assignment");
            }
            Object lunchStart = input.lunchStartsAt() == null
                ? null
                : day.atTime(input.lunchStartsAt()).atZone(ZONE).toOffsetDateTime();
            Object lunchEnd =
                input.lunchEndsAt() == null ? null : day.atTime(input.lunchEndsAt()).atZone(ZONE).toOffsetDateTime();
            UUID saved = id;
            if (id == null) {
                saved = db.insert(
                    "consultant_work_shifts",
                    fields(
                        "consultant_user_id",
                        input.consultantId(),
                        "assigned_by",
                        user.id(),
                        "starts_at",
                        start,
                        "ends_at",
                        end,
                        "lunch_starts_at",
                        lunchStart,
                        "lunch_ends_at",
                        lunchEnd,
                        "series_id",
                        series
                    )
                );
            } else {
                db.update(
                    "UPDATE consultant_work_shifts SET starts_at=?,ends_at=?,lunch_starts_at=?,lunch_ends_at=?,"
                        + "assigned_by=? WHERE id=?",
                    start,
                    end,
                    lunchStart,
                    lunchEnd,
                    user.id(),
                    id
                );
            }
            result.add(db.one("SELECT * FROM consultant_work_shifts WHERE id=?", saved));
        }
        if (result.isEmpty()) {
            throw ApiException.badRequest("No dates match the selected weekdays");
        }
        return result;
    }

    @Transactional
    public Map<String, Object> cancel(
        UUID id) {
        user.requireOperationsRole();
        var before = db.one("SELECT * FROM consultant_work_shifts WHERE id=?", id);
        bookings.lockConsultant((UUID) before.get("consultant_user_id"));
        var row = db.one("SELECT * FROM consultant_work_shifts WHERE id=? FOR UPDATE", id);
        protect(row);
        db.update("UPDATE consultant_work_shifts SET cancelled_at=COALESCE(cancelled_at,now()) WHERE id=?", id);
        return fields("id", id, "cancelled", true);
    }

    private void protect(
        Map<String, Object> row) {
        if (!WorkShiftSlots.time(row.get("starts_at")).isAfter(OffsetDateTime.now())) {
            throw ApiException.conflict("A started shift cannot be changed");
        }
        if (bookings.busyOverlapsBooking(
            (UUID) row.get("consultant_user_id"),
            WorkShiftSlots.time(row.get("starts_at")),
            WorkShiftSlots.time(row.get("ends_at"))
        )) {
            throw ApiException.conflict("Shift has pending or accepted bookings; resolve bookings before changing it");
        }
    }

    private static void range(
        OffsetDateTime from,
        OffsetDateTime to) {
        if (!to.isAfter(from) || to.isAfter(from.plusDays(93))) {
            throw ApiException.badRequest("Range must be positive and at most 93 days");
        }
    }
}
