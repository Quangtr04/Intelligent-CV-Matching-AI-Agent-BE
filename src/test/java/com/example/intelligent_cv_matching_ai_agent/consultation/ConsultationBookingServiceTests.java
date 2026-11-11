package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationRatingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantBusyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationBookingServiceTests {

    private final ConsultationBookingRepository db = mock(ConsultationBookingRepository.class);

    private final ConsultationBookingService service = new ConsultationBookingService(db, new CurrentUser());

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultant = UUID.randomUUID();

    private final UUID credit = UUID.randomUUID();

    private final UUID booking = UUID.randomUUID();

    private final UUID thread = UUID.randomUUID();

    private final UUID resume = UUID.randomUUID();

    @BeforeEach
    void setup() {
        login(candidate, "CANDIDATE");
        when(db.consultant(consultant)).thenReturn(Map.of("target_roles", List.of("Back-end Developer")));
        when(db.credit(credit, candidate)).thenReturn(paidCredit(1, 30));
        when(db.createThread(anyMap())).thenReturn(thread);
        when(db.insert(anyMap())).thenReturn(booking);
        when(db.booking(booking)).thenReturn(row("PENDING", OffsetDateTime.now().plusDays(1)));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingRoleAliasesReserveOnePaidSessionBeforeCreatingAssignedThread() {
        service.create(request(30));
        var ordered = inOrder(db);
        ordered.verify(db).lockPeople(candidate, consultant);
        ordered.verify(db).requireReadyResume(resume, candidate);
        ordered.verify(db).consultant(consultant);
        ordered.verify(db).overlaps(eq(candidate), eq(consultant), any(), any());
        ordered.verify(db).credit(credit, candidate);
        ordered.verify(db).reserveCredit(credit);
        ArgumentCaptor<Map<String, Object>> capture = ArgumentCaptor.forClass(Map.class);
        ordered.verify(db).createThread(capture.capture());
        assertEquals(consultant, capture.getValue().get("consultant_user_id"));
        assertEquals(candidate, capture.getValue().get("candidate_user_id"));
        assertEquals("SESSION_BOOKING", capture.getValue().get("request_type"));
        verify(db).inform(eq(consultant), eq(thread), eq("CONSULTATION_BOOKING_REQUEST"), anyString(), anyString());
    }

    @Test
    void noCreditOrUnpaidCreditCannotCreateThread() {
        for (Map<String, Object> value : List.of(
            paidCredit(0, 30),
            fields("status", "PENDING_PAYMENT", "is_session_pack", true, "sessions_remaining", 1),
            fields("status", "PAID", "is_session_pack", false, "sessions_remaining", 1)
        )) {
            when(db.credit(credit, candidate)).thenReturn(value);
            assertEquals(409, assertThrows(ApiException.class, () -> service.create(request(30))).status());
        }
        verify(db, never()).reserveCredit(any());
        verify(db, never()).createThread(anyMap());
    }

    @Test
    void scheduleConflictDoesNotConsumePaidSession() {
        when(db.overlaps(eq(candidate), eq(consultant), any(), any())).thenReturn(true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.create(request(30))).status());
        verify(db, never()).credit(any(), any());
        verify(db, never()).reserveCredit(any());
    }

    @Test
    void unrelatedConsultantAndUnsupportedDurationAreRejected() {
        when(db.consultant(consultant)).thenReturn(Map.of("target_roles", List.of("Frontend")));
        assertThrows(ApiException.class, () -> service.create(request(30)));
        when(db.consultant(consultant)).thenReturn(Map.of("target_roles", List.of("BE")));
        assertThrows(ApiException.class, () -> service.create(request(45)));
        verify(db, never()).reserveCredit(any());
    }

    @Test
    void oneCreditCanBookEitherDurationRegardlessOfLegacyPackDuration() {
        when(db.credit(credit, candidate)).thenReturn(paidCredit(2, 30));
        service.create(request(60));
        when(db.credit(credit, candidate)).thenReturn(paidCredit(1, 60));
        service.create(request(30));
        verify(db, times(2)).reserveCredit(credit);
    }

    @Test
    void directoryFiltersAliasesWithoutChangingRepositoryRanking() {
        var backend = Map.<String, Object>of("id", consultant, "target_roles", List.of("Backend Developer"));
        when(db.consultants())
            .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "target_roles", List.of("FE")), backend));
        assertEquals(List.of(backend), service.consultants("BE"));
    }

    @Test
    void onlySelectedConsultantCanAccept() {
        Map<String, Object> row = row("PENDING", OffsetDateTime.now().plusDays(1));
        when(db.lockBooking(booking)).thenReturn(row);
        login(UUID.randomUUID(), "CAREER_CONSULTANT");
        assertEquals(404, assertThrows(ApiException.class, () -> service.accept(booking)).status());
        verify(db, never()).accept(any());
        login(consultant, "CAREER_CONSULTANT");
        service.accept(booking);
        verify(db).accept(booking);
    }

    @Test
    void declineRefundsOnceAndCannotBeReplayed() {
        login(consultant, "CAREER_CONSULTANT");
        when(db.lockBooking(booking)).thenReturn(row("PENDING", OffsetDateTime.now().plusDays(1)));
        service.decline(booking);
        verify(db).refundCredit(credit);
        verify(db).end(booking, "DECLINED", true);
        when(db.lockBooking(booking)).thenReturn(row("DECLINED", OffsetDateTime.now().plusDays(1)));
        assertThrows(ApiException.class, () -> service.decline(booking));
        verify(db, times(1)).refundCredit(credit);
    }

    @Test
    void candidateCanCancelAcceptedFutureBookingButCannotCancelAfterStart() {
        when(db.lockBooking(booking)).thenReturn(row("ACCEPTED", OffsetDateTime.now().plusHours(1)));
        service.cancel(booking);
        verify(db).refundCredit(credit);
        when(db.lockBooking(booking)).thenReturn(row("ACCEPTED", OffsetDateTime.now().minusMinutes(1)));
        assertThrows(ApiException.class, () -> service.cancel(booking));
        verify(db, times(1)).refundCredit(credit);
    }

    @Test
    void completionAndRatingAreUnavailableBeforeScheduledEnd() {
        var row = row("ACCEPTED", OffsetDateTime.now().minusMinutes(1));
        when(db.lockBooking(booking)).thenReturn(row);
        login(consultant, "CAREER_CONSULTANT");
        assertThrows(ApiException.class, () -> service.complete(booking));
        login(candidate, "CANDIDATE");
        row.put("status", "COMPLETED");
        assertThrows(ApiException.class, () -> service.rate(booking, new ConsultationRatingRequest(5, "Helpful")));
        verify(db, never()).end(any(), anyString(), anyBoolean());
        verify(db, never()).rate(anyMap());
    }

    @Test
    void automaticCompletionAsksForRatingAndNeverRefundsConsumedSession() {
        var row = row("ACCEPTED", OffsetDateTime.now().minusHours(1));
        when(db.lockBooking(booking)).thenReturn(row);
        SecurityContextHolder.clearContext();
        service.settleDue(booking);
        verify(db).end(booking, "COMPLETED", false);
        verify(db).closeThread(thread);
        verify(db).inform(eq(candidate), eq(thread), eq("CONSULTATION_RATING_REQUEST"), anyString(), anyString());
        verify(db, never()).refundCredit(any());
    }

    @Test
    void unacceptedExpiredRequestRefundsCandidateAndFreesSchedule() {
        when(db.lockBooking(booking)).thenReturn(row("PENDING", OffsetDateTime.now().minusMinutes(1)));
        SecurityContextHolder.clearContext();
        service.settleDue(booking);
        verify(db).end(booking, "DECLINED", true);
        verify(db).refundCredit(credit);
        verify(db).closeThread(thread);
    }

    @Test
    void onlyOwnerCanReviewCompletedConsultationAndOnlyOnce() {
        var row = row("COMPLETED", OffsetDateTime.now().minusHours(1));
        when(db.booking(booking)).thenReturn(row);
        when(db.lockBooking(booking)).thenReturn(row);
        login(UUID.randomUUID(), "CANDIDATE");
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.rate(booking, new ConsultationRatingRequest(5, "Good")))
                .status()
        );
        login(candidate, "CANDIDATE");
        service.rate(booking, new ConsultationRatingRequest(5, "Helpful consultation"));
        verify(db).rate(
            argThat(v -> candidate.equals(v.get("candidate_user_id")) && Integer.valueOf(5).equals(v.get("rating")))
        );
        row.put("rating", 5);
        assertThrows(ApiException.class, () -> service.rate(booking, new ConsultationRatingRequest(4, "Again")));
        verify(db, times(1)).rate(anyMap());
    }

    @Test
    void consultantCannotOverwriteBookedTimeWithBusyBlock() {
        login(consultant, "CAREER_CONSULTANT");
        when(db.busyOverlapsBooking(eq(consultant), any(), any())).thenReturn(true);
        assertThrows(
            ApiException.class,
            () -> service.addBusy(
                new ConsultantBusyRequest(
                    OffsetDateTime.now().plusDays(1),
                    OffsetDateTime.now().plusDays(1).plusHours(2),
                    "Private appointment"
                )
            )
        );
        verify(db, never()).addBusy(any(), any(), any(), anyString());
    }

    @Test
    void profileRejectsBlankRolesAndDeduplicatesAliases() {
        login(consultant, "CAREER_CONSULTANT");
        assertThrows(ApiException.class, () -> service.saveProfile(new ConsultantProfileRequest(List.of(" "), "")));
        service.saveProfile(new ConsultantProfileRequest(List.of("BE", "Backend", "FE"), "About me"));
        verify(db).saveProfile(consultant, List.of("BE", "FE"), "About me");
    }

    @Test
    void omittedDurationReservesOneHourAndConsumesOneCredit() {
        service.create(request(null));
        ArgumentCaptor<Map<String, Object>> capture = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(capture.capture());
        assertEquals(60, capture.getValue().get("duration_minutes"));
        assertEquals(
            ((OffsetDateTime) capture.getValue().get("starts_at")).plusMinutes(60),
            capture.getValue().get("ends_at")
        );
        verify(db).reserveCredit(credit);
    }

    @Test
    void approvalReplayNeverQueuesAnotherMeetingOrNotification() {
        var row = row("ACCEPTED", OffsetDateTime.now().plusDays(1));
        row.put("meeting_status", "READY");
        when(db.lockBooking(booking)).thenReturn(row);
        when(db.booking(booking)).thenReturn(row);
        login(consultant, "CAREER_CONSULTANT");
        assertEquals("READY", service.accept(booking).get("meeting_status"));
        verify(db, never()).accept(any());
        verify(db, never()).inform(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void meetingsLongerThanOneHourNeverConsumeCredit() {
        for (int duration : List.of(0, 61, 90, 120)) {
            assertThrows(ApiException.class, () -> service.create(request(duration)));
        }
        verify(db, never()).reserveCredit(any());
    }

    @Test
    void failedMeetingRefundsOnAutomaticAndManualCompletion() {
        var row = row("ACCEPTED", OffsetDateTime.now().minusHours(2));
        row.put("meeting_status", "FAILED");
        when(db.lockBooking(booking)).thenReturn(row);
        login(consultant, "CAREER_CONSULTANT");
        service.complete(booking);
        verify(db).end(booking, "DECLINED", true);
        verify(db).refundCredit(credit);
        row.put("status", "DECLINED");
        service.settleDue(booking);
        verify(db, times(1)).refundCredit(credit);
        verify(db, never()).end(booking, "COMPLETED", false);
    }

    @Test
    void automaticCompletionRefundsReadyMeetingThatNeverOpened() {
        var row = row("ACCEPTED", OffsetDateTime.now().minusHours(2));
        row.put("meeting_status", "READY");
        when(db.lockBooking(booking)).thenReturn(row);
        service.settleDue(booking);
        verify(db).end(booking, "DECLINED", true);
        verify(db).refundCredit(credit);
    }

    @Test
    void openedGoogleMeetingCompletesWithoutRefund() {
        var row = row("ACCEPTED", OffsetDateTime.now().minusHours(2));
        row.put("meeting_status", "ENDED");
        row.put("meeting_opened_at", OffsetDateTime.now().minusHours(2));
        when(db.lockBooking(booking)).thenReturn(row);
        service.settleDue(booking);
        verify(db).end(booking, "COMPLETED", false);
        verify(db, never()).refundCredit(any());
    }

    @Test
    void retryRequiresParticipantAndUnexpiredAcceptedBooking() {
        var row = row("ACCEPTED", OffsetDateTime.now().plusHours(2));
        when(db.lockBooking(booking)).thenReturn(row);
        when(db.booking(booking)).thenReturn(row);
        login(UUID.randomUUID(), "CANDIDATE");
        assertEquals(404, assertThrows(ApiException.class, () -> service.retryMeeting(booking)).status());
        verify(db, never()).lockPeople(any(), any());
        login(candidate, "CANDIDATE");
        service.retryMeeting(booking);
        verify(db).retryMeeting(booking);
        row.put("ends_at", OffsetDateTime.now().minusSeconds(1));
        assertThrows(ApiException.class, () -> service.retryMeeting(booking));
        verify(db, times(1)).retryMeeting(booking);
    }

    private ConsultationBookingRequest request(
        Integer minutes) {
        return new ConsultationBookingRequest(
            consultant,
            credit,
            "BE",
            "Backend interview",
            "Review my CV",
            OffsetDateTime.now().plusDays(1),
            minutes,
            resume,
            null
        );
    }

    private Map<String, Object> paidCredit(
        int remaining,
        int minutes) {
        return fields(
            "status",
            "PAID",
            "is_session_pack",
            true,
            "delivery_mode",
            "LIVE",
            "sessions_remaining",
            remaining,
            "duration_minutes",
            minutes
        );
    }

    private Map<String, Object> row(
        String status,
        OffsetDateTime start) {
        return fields(
            "id",
            booking,
            "candidate_user_id",
            candidate,
            "consultant_user_id",
            consultant,
            "credit_order_id",
            credit,
            "thread_id",
            thread,
            "status",
            status,
            "starts_at",
            start,
            "ends_at",
            start.plusMinutes(30),
            "subject",
            "Backend interview",
            "credit_refunded",
            false
        );
    }

    private void login(
        UUID id,
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                id.toString(),
                "",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}
