package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.WorkShiftRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

class WorkShiftTests {

    private final SqlStore db = mock(SqlStore.class);

    private final ConsultationBookingRepository bookings = mock(ConsultationBookingRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final WorkShiftService service = new WorkShiftService(db, bookings, user);

    private final UUID consultant = UUID.randomUUID();

    private final UUID manager = UUID.randomUUID();

    private final OffsetDateTime start =
        LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10).atTime(7, 0).atOffset(ZoneOffset.ofHours(7));

    private Map<String, Object> shift() {
        return fields(
            "id",
            UUID.randomUUID(),
            "consultant_user_id",
            consultant,
            "starts_at",
            start,
            "ends_at",
            start.plusHours(10)
        );
    }

    private WorkShiftRequest request(
        LocalDate from,
        LocalDate to,
        Set<DayOfWeek> weekdays) {
        return new WorkShiftRequest(
            consultant,
            from,
            to,
            LocalTime.of(7, 0),
            LocalTime.of(17, 0),
            null,
            null,
            weekdays
        );
    }

    @Test
    void fixedGridMatchesExampleAndDoesNotOverflow() {
        var slots = WorkShiftSlots.generate(shift(), 60);
        assertEquals(8, slots.size());
        for (int i = 0; i < 8; i++)
            assertEquals(start.plusMinutes(i * 75), slots.get(i).startsAt());
        assertEquals(start.plusHours(9).plusMinutes(45), slots.getLast().endsAt());
    }

    @Test
    void lunchFiltersGridWithoutReanchoringIt() {
        var shift = shift();
        shift.put("lunch_starts_at", start.plusHours(5));
        shift.put("lunch_ends_at", start.plusHours(6));
        var slots = WorkShiftSlots.generate(shift, 60);
        assertEquals(7, slots.size());
        assertEquals(start.plusHours(6).plusMinutes(15), slots.get(4).startsAt());
    }

    @Test
    void preservesThirtyMinuteDuration() {
        var slots = WorkShiftSlots.generate(shift(), 30);
        assertEquals(13, slots.size());
        assertEquals(start.plusMinutes(45), slots.get(1).startsAt());
    }

    @Test
    void weeklyAssignmentExpandsOnlyMatchingDaysAndUsesConsultantLock() {
        var from = start.toLocalDate();
        when(user.id()).thenReturn(manager);
        when(db.insert(eq("consultant_work_shifts"), anyMap())).thenReturn(UUID.randomUUID());
        when(db.one(anyString(), any())).thenReturn(shift());
        service.save(null, request(from, from.plusDays(13), Set.of(from.getDayOfWeek())));
        var order = inOrder(user, bookings, db);
        order.verify(user).requireOperationsRole();
        order.verify(bookings).lockConsultant(consultant);
        verify(db, times(2)).insert(eq("consultant_work_shifts"), anyMap());
    }

    @Test
    void overlappingShiftRejectedBeforeInsert() {
        when(db.optional(anyString(), any(), any(), any(), any())).thenReturn(Optional.of(shift()));
        assertThrows(
            ApiException.class,
            () -> service.save(null, request(start.toLocalDate(), start.toLocalDate(), Set.of()))
        );
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void lunchMustBeInsideShift() {
        var date = start.toLocalDate();
        var input = new WorkShiftRequest(
            consultant,
            date,
            date,
            LocalTime.of(7, 0),
            LocalTime.of(17, 0),
            LocalTime.of(6, 0),
            LocalTime.of(8, 0),
            Set.of()
        );
        assertThrows(ApiException.class, () -> service.save(null, input));
        verify(bookings, never()).lockConsultant(any());
    }

    @Test
    void activeBookingPreventsCancellationAfterAcquiringSameLockAsBooking() {
        var shift = shift();
        var id = (UUID) shift.get("id");
        when(db.one(anyString(), eq(id))).thenReturn(shift);
        when(bookings.busyOverlapsBooking(eq(consultant), any(), any())).thenReturn(true);
        assertThrows(ApiException.class, () -> service.cancel(id));
        verify(bookings).lockConsultant(consultant);
        verify(db, never()).update(anyString(), any());
    }

    @Test
    void activeBookingPreventsEditingAssignedOccurrence() {
        var shift = shift();
        var id = (UUID) shift.get("id");
        when(db.one(anyString(), eq(id), eq(consultant))).thenReturn(shift);
        when(bookings.busyOverlapsBooking(eq(consultant), any(), any())).thenReturn(true);
        assertThrows(
            ApiException.class,
            () -> service.save(id, request(start.toLocalDate(), start.toLocalDate(), Set.of()))
        );
        verify(db, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void bookingAndCancellationOnlyFilterFixedSlots() {
        when(user.id()).thenReturn(manager);
        when(db.list(anyString(), eq(consultant), any(), any())).thenReturn(List.of(shift()));
        when(bookings.availability(eq(consultant), any(), any()))
            .thenReturn(List.of(fields("starts_at", start, "ends_at", start.plusHours(1))));
        var occupied = service.slots(consultant, start, start.plusHours(10), 60);
        assertEquals(7, occupied.size());
        assertEquals(start.plusMinutes(75), occupied.getFirst().get("starts_at"));
        when(bookings.availability(eq(consultant), any(), any())).thenReturn(List.of());
        var free = service.slots(consultant, start, start.plusHours(10), 60);
        assertEquals(8, free.size());
        assertEquals(occupied, free.subList(1, 8));
    }

    @Test
    void bookingGuardRejectsNonGridTimeAndKeepsFifteenMinuteBreak() {
        var repo = new ConsultationBookingRepository(db);
        when(db.list(anyString(), eq(consultant), any(), any())).thenReturn(List.of(shift()));
        assertThrows(
            ApiException.class,
            () -> repo.requireAssignedSlot(consultant, start.plusMinutes(1), start.plusMinutes(61), 60)
        );
        repo.requireAssignedSlot(consultant, start, start.plusHours(1), 60);
        when(db.optional(anyString(), eq(consultant), any(), any()))
            .thenReturn(Optional.of(Map.of("id", UUID.randomUUID())));
        assertThrows(ApiException.class, () -> repo.requireAssignedSlot(consultant, start, start.plusHours(1), 60));
    }

    @Test
    void consultantCannotAssignOrChangeOwnShifts() {
        doThrow(ApiException.forbidden("Manager required")).when(user).requireOperationsRole();
        assertThrows(
            ApiException.class,
            () -> service.save(null, request(start.toLocalDate(), start.toLocalDate(), Set.of()))
        );
        assertThrows(ApiException.class, () -> service.cancel(UUID.randomUUID()));
        verifyNoInteractions(db, bookings);
    }
}
