package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationMeetingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationMeetingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.GoogleConsultationMeetingClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationMeetingServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-06T05:00:00Z");

    private static final String SPACE = "spaces/consultationTestSpace";

    private static final String MEETING_URL = "https://meet.google.com/abc-defg-hij";

    private static final String EVENT_URL = "https://calendar.google.com/calendar/event?eid=synthetic";

    private final ConsultationMeetingRepository db = mock(ConsultationMeetingRepository.class);

    private final GoogleConsultationMeetingClient google = mock(GoogleConsultationMeetingClient.class);

    private final ConsultationMeetingService service =
        new ConsultationMeetingService(db, google, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID bookingId = UUID.randomUUID();

    private final Map<String, Object> stored = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        stored.put("id", bookingId);
        stored.put("status", "ACCEPTED");
        stored.put("starts_at", NOW.plusSeconds(3600));
        stored.put("ends_at", NOW.plusSeconds(7200));
        stored.put("meeting_status", "PENDING");
        stored.put("candidate_email", "candidate@example.test");
        stored.put("consultant_email", "consultant@example.test");
        when(db.get(bookingId)).thenAnswer(invocation -> new LinkedHashMap<>(stored));
        when(db.claim(eq(bookingId), any(UUID.class))).thenAnswer(invocation -> {
            stored.put("meeting_lease_token", invocation.getArgument(1));
            stored.put("meeting_lease_until", NOW.plusSeconds(600));
            return true;
        });
        when(db.renew(eq(bookingId), any(UUID.class))).thenAnswer(invocation -> {
            if (!owns(invocation.getArgument(1))) {
                return false;
            }
            stored.put("meeting_lease_until", NOW.plusSeconds(600));
            return true;
        });
        when(db.saveSpace(eq(bookingId), any(UUID.class), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                if (!owns(invocation.getArgument(1))) {
                    return false;
                }
                stored.put("meeting_space_name", invocation.getArgument(2));
                stored.put("meeting_url", invocation.getArgument(3));
                stored.put("calendar_event_id", invocation.getArgument(4));
                return true;
            });
        when(db.ready(eq(bookingId), any(UUID.class), anyString(), anyString())).thenAnswer(invocation -> {
            if (!owns(invocation.getArgument(1)) || !active()) {
                return false;
            }
            stored.put("meeting_status", "READY");
            stored.put("calendar_event_id", invocation.getArgument(2));
            stored.put("calendar_event_url", invocation.getArgument(3));
            return true;
        });
        when(db.opened(eq(bookingId), any(UUID.class))).thenAnswer(invocation -> {
            if (!owns(invocation.getArgument(1)) || !active() || ((Instant) stored.get("starts_at")).isAfter(NOW)) {
                return false;
            }
            stored.put("meeting_opened_at", NOW);
            return true;
        });
        doAnswer(invocation -> {
            if (owns(invocation.getArgument(1))) {
                stored.put("meeting_ended_at", NOW);
                stored.put("meeting_status", Boolean.TRUE.equals(invocation.getArgument(2)) ? "CANCELLED" : "ENDED");
            }
            return null;
        }).when(db).ended(eq(bookingId), any(UUID.class), anyBoolean());
        doAnswer(invocation -> {
            if (owns(invocation.getArgument(1))) {
                stored.remove("meeting_lease_token");
                stored.remove("meeting_lease_until");
            }
            return null;
        }).when(db).release(eq(bookingId), any(UUID.class));
        when(google.createSpace()).thenReturn(new GoogleConsultationMeetingClient.MeetingSpace(SPACE, MEETING_URL));
        when(google.createEvent(eq(bookingId), anyMap(), eq(MEETING_URL))).thenReturn(event());
    }

    @Test
    void newMeetingPersistsTheSpaceBeforeSendingCalendarInvitations() {
        service.process(bookingId);
        var order = inOrder(db, google);
        order.verify(google).createSpace();
        order.verify(db).saveSpace(
            eq(bookingId),
            any(UUID.class),
            eq(SPACE),
            eq(MEETING_URL),
            eq(GoogleConsultationMeetingClient.eventId(bookingId))
        );
        order.verify(google)
            .createEvent(eq(bookingId), argThat(row -> SPACE.equals(row.get("meeting_space_name"))), eq(MEETING_URL));
        order.verify(db).ready(eq(bookingId), any(UUID.class), eq(event().id()), eq(EVENT_URL));
        assertEquals("READY", stored.get("meeting_status"));
        verify(google, never()).openSpace(anyString());
    }

    @Test
    void retryAfterCalendarFailureReusesTheDurableSpace() {
        existingSpace("FAILED");
        service.process(bookingId);
        verify(google, never()).createSpace();
        verify(google).createEvent(eq(bookingId), anyMap(), eq(MEETING_URL));
        assertEquals(SPACE, stored.get("meeting_space_name"));
        assertEquals("READY", stored.get("meeting_status"));
    }

    @Test
    void readyRoomDoesNotOpenBeforeTheAcceptedStartTime() {
        existingSpace("READY");
        service.process(bookingId);
        verifyNoInteractions(google);
        assertNull(stored.get("meeting_opened_at"));
        verify(db).release(eq(bookingId), any(UUID.class));
    }

    @Test
    void readyRoomOpensAtTheStartBoundaryAndRecordsDelivery() {
        existingSpace("READY");
        stored.put("starts_at", NOW);
        stored.put("ends_at", NOW.plusSeconds(3600));
        service.process(bookingId);
        verify(google).openSpace(SPACE);
        assertEquals(NOW, stored.get("meeting_opened_at"));
        verify(google, never()).createSpace();
        verify(google, never()).cancelEvent(any());
    }

    @Test
    void deliveredRoomRestrictsAndEndsAtTheEndBoundary() {
        deliveredAndEnded();
        service.process(bookingId);
        var order = inOrder(google);
        order.verify(google).restrictSpace(SPACE);
        order.verify(google).endConference(SPACE);
        verify(google, never()).openSpace(anyString());
        verify(google, never()).cancelEvent(any());
        verify(db).ended(eq(bookingId), any(UUID.class), eq(false));
        assertEquals("ENDED", stored.get("meeting_status"));
    }

    @Test
    void cancelledAppointmentClosesTheRoomAndCancelsBothPartyInvitationEvent() {
        existingSpace("READY");
        stored.put("status", "CANCELLED");
        service.process(bookingId);
        verify(google).restrictSpace(SPACE);
        verify(google).endConference(SPACE);
        verify(google).cancelEvent(bookingId);
        verify(db).ended(eq(bookingId), any(UUID.class), eq(true));
    }

    @Test
    void cleanupStillAttemptsEndAndCalendarCancellationWhenRestrictionFails() {
        existingSpace("READY");
        stored.put("status", "CANCELLED");
        doThrow(new ApiException(503, "Google access restriction is temporarily unavailable")).when(google)
            .restrictSpace(SPACE);
        service.process(bookingId);
        verify(google).endConference(SPACE);
        verify(google).cancelEvent(bookingId);
        verify(db, never()).ended(any(), any(), anyBoolean());
        verify(db).failed(eq(bookingId), any(UUID.class), contains("temporarily unavailable"));
        verify(db).release(eq(bookingId), any(UUID.class));
        assertNull(stored.get("meeting_ended_at"));
    }

    @Test
    void cancellationDuringSpaceCreationPreventsCalendarInvitations() {
        when(google.createSpace()).thenAnswer(invocation -> {
            stored.put("status", "CANCELLED");
            return new GoogleConsultationMeetingClient.MeetingSpace(SPACE, MEETING_URL);
        });
        service.process(bookingId);
        verify(google, never()).createEvent(any(), anyMap(), anyString());
        verify(google).restrictSpace(SPACE);
        verify(google).endConference(SPACE);
        verify(google, never()).openSpace(anyString());
    }

    @Test
    void lateCalendarSuccessAfterCancellationAndLeaseLossQueuesCompensatingCleanup() {
        existingSpace("PENDING");
        when(google.createEvent(eq(bookingId), anyMap(), eq(MEETING_URL))).thenAnswer(invocation -> {
            cancelledByAnotherWorker();
            return event();
        });
        service.process(bookingId);
        verify(db).requeueCleanup(bookingId);
        verify(google).restrictSpace(SPACE);
        verify(google).endConference(SPACE);
        verify(google).cancelEvent(bookingId);
        assertEquals("CANCELLED", stored.get("status"));
        assertNotEquals("READY", stored.get("meeting_status"));
    }

    @Test
    void lateOpenAfterCancellationAndLeaseLossQueuesCompensatingCleanup() {
        existingSpace("READY");
        stored.put("starts_at", NOW.minusSeconds(60));
        stored.put("ends_at", NOW.plusSeconds(3540));
        doAnswer(invocation -> {
            cancelledByAnotherWorker();
            return null;
        }).when(google).openSpace(SPACE);
        service.process(bookingId);
        verify(db).requeueCleanup(bookingId);
        verify(google).restrictSpace(SPACE);
        verify(google).endConference(SPACE);
        verify(google).cancelEvent(bookingId);
        assertNull(stored.get("meeting_opened_at"));
    }

    @Test
    void losingLeaseDoesNotTerminateAStillActiveMeetingOwnedByANewerWorker() {
        existingSpace("READY");
        stored.put("starts_at", NOW.minusSeconds(60));
        stored.put("ends_at", NOW.plusSeconds(3540));
        doAnswer(invocation -> {
            stored.put("meeting_lease_token", UUID.randomUUID());
            stored.put("meeting_lease_until", NOW.plusSeconds(600));
            stored.put("meeting_opened_at", NOW);
            return null;
        }).when(google).openSpace(SPACE);
        service.process(bookingId);
        verify(google, never()).restrictSpace(anyString());
        verify(google, never()).endConference(anyString());
        verify(google, never()).cancelEvent(any());
        assertEquals("ACCEPTED", stored.get("status"));
    }

    @Test
    void spaceCreatedAfterLeaseLossIsNeverAdvertisedAndIsClosed() {
        when(google.createSpace()).thenAnswer(invocation -> {
            stored.put("meeting_lease_token", UUID.randomUUID());
            stored.put("meeting_lease_until", NOW.plusSeconds(600));
            return new GoogleConsultationMeetingClient.MeetingSpace(SPACE, MEETING_URL);
        });
        service.process(bookingId);
        verify(google).restrictSpace(SPACE);
        verify(google).endConference(SPACE);
        verify(google, never()).createEvent(any(), anyMap(), anyString());
        assertNull(stored.get("meeting_space_name"));
    }

    @Test
    void orphanCleanupStillEndsConferenceWhenRestrictionFails() {
        when(google.createSpace()).thenAnswer(invocation -> {
            stored.put("meeting_lease_token", UUID.randomUUID());
            return new GoogleConsultationMeetingClient.MeetingSpace(SPACE, MEETING_URL);
        });
        doThrow(new ApiException(503, "Google access restriction is temporarily unavailable")).when(google)
            .restrictSpace(SPACE);
        service.process(bookingId);
        verify(google).endConference(SPACE);
        verify(google, never()).createEvent(any(), anyMap(), anyString());
        assertNull(stored.get("meeting_url"));
    }

    @Test
    void overSixtyMinuteWindowMakesNoProviderCall() {
        stored.put("starts_at", NOW.plusSeconds(3600));
        stored.put("ends_at", NOW.plusSeconds(7201));
        service.process(bookingId);
        verifyNoInteractions(google);
        verify(db).failed(eq(bookingId), any(UUID.class), contains("60"));
        verify(db, never()).ready(any(), any(), anyString(), anyString());
    }

    @Test
    void disabledProviderRecordsRetryableFailureWithoutCompletingOrAdvertisingMeeting() {
        when(google.createSpace()).thenThrow(new ApiException(503, "Google consultation meetings are disabled"));
        service.process(bookingId);
        verify(db).failed(eq(bookingId), any(UUID.class), contains("disabled"));
        verify(db).release(eq(bookingId), any(UUID.class));
        verify(db, never()).ready(any(), any(), anyString(), anyString());
        verify(db, never()).ended(any(), any(), anyBoolean());
        verify(google, never()).createEvent(any(), anyMap(), anyString());
        assertNull(stored.get("meeting_url"));
    }

    @Test
    void unexpectedProviderExceptionDoesNotPersistRawSensitiveBody() {
        when(google.createSpace()).thenThrow(new IllegalStateException("sensitive provider response"));
        service.process(bookingId);
        verify(db).failed(
            eq(bookingId),
            any(UUID.class),
            argThat(error -> !error.contains("sensitive provider response") && error.contains("retry"))
        );
        verify(db).release(eq(bookingId), any(UUID.class));
    }

    @Test
    void competingWorkerThatCannotClaimDoesNotCallProviderOrReleaseAnotherLease() {
        when(db.claim(eq(bookingId), any(UUID.class))).thenReturn(false);
        service.process(bookingId);
        verifyNoInteractions(google);
        verify(db, never()).get(any());
        verify(db, never()).release(any(), any());
    }

    private void existingSpace(
        String status) {
        stored.put("meeting_status", status);
        stored.put("meeting_space_name", SPACE);
        stored.put("meeting_url", MEETING_URL);
        stored.put("calendar_event_id", GoogleConsultationMeetingClient.eventId(bookingId));
        stored.put("calendar_event_url", EVENT_URL);
    }

    private void deliveredAndEnded() {
        existingSpace("READY");
        stored.put("starts_at", NOW.minusSeconds(3600));
        stored.put("ends_at", NOW);
        stored.put("meeting_opened_at", NOW.minusSeconds(3500));
    }

    private void cancelledByAnotherWorker() {
        stored.put("status", "CANCELLED");
        stored.put("meeting_status", "CANCELLED");
        stored.put("meeting_ended_at", NOW);
        stored.put("meeting_lease_token", UUID.randomUUID());
        stored.put("meeting_lease_until", NOW.plusSeconds(600));
    }

    private boolean active() {
        return "ACCEPTED".equals(stored.get("status")) && ((Instant) stored.get("ends_at")).isAfter(NOW);
    }

    private boolean owns(
        UUID lease) {
        return lease.equals(stored.get("meeting_lease_token"))
            && ((Instant) stored.get("meeting_lease_until")).isAfter(NOW);
    }

    private GoogleConsultationMeetingClient.CalendarEvent event() {
        return new GoogleConsultationMeetingClient.CalendarEvent(
            GoogleConsultationMeetingClient.eventId(bookingId),
            EVENT_URL
        );
    }
}
