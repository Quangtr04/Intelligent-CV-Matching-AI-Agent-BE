package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationRatingRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationBookingScenarioTests {

    private final ConsultationBookingRepository db = mock(ConsultationBookingRepository.class);

    private final ConsultationBookingService service = new ConsultationBookingService(db, new CurrentUser());

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultant = UUID.randomUUID();

    private final UUID credit = UUID.randomUUID();

    private final UUID booking = UUID.randomUUID();

    private final UUID thread = UUID.randomUUID();

    @BeforeEach
    void setup() {
        login(candidate, "CANDIDATE");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("invalidRatings")
    void invalidRatingOrCommentNeverWritesReview(
        Integer rating,
        String comment) {
        Map<String, Object> completed = row("COMPLETED", OffsetDateTime.now().minusHours(2));
        when(db.booking(booking)).thenReturn(completed);
        when(db.lockBooking(booking)).thenReturn(completed);

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.rate(booking, new ConsultationRatingRequest(rating, comment))
        );

        assertEquals(400, failure.status());
        verify(db, never()).rate(anyMap());
        verify(db, never()).refundCredit(any());
        verify(db, never()).end(any(), anyString(), anyBoolean());
    }

    private static Stream<Arguments> invalidRatings() {
        return Stream.of(
            Arguments.of(null, "Helpful"),
            Arguments.of(0, "Helpful"),
            Arguments.of(6, "Helpful"),
            Arguments.of(5, null),
            Arguments.of(5, "  \n\t"),
            Arguments.of(5, "x".repeat(2001))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void ratingEndpointsPersistWithTrimmedCommentAndServerOwnedParticipants(
        int rating) {
        Map<String, Object> completed = row("COMPLETED", OffsetDateTime.now().minusHours(2));
        when(db.booking(booking)).thenReturn(completed);
        when(db.lockBooking(booking)).thenReturn(completed);

        service.rate(booking, new ConsultationRatingRequest(rating, "  Helpful consultation  "));

        verify(db).rate(
            argThat(
                values -> booking.equals(values.get("booking_id")) && candidate.equals(values.get("candidate_user_id"))
                    && consultant.equals(values.get("consultant_user_id"))
                    && Integer.valueOf(rating).equals(values.get("rating"))
                    && "Helpful consultation".equals(values.get("comment"))
            )
        );
    }

    @Test
    void twoThousandCharacterReviewIsAcceptedWithoutTruncation() {
        Map<String, Object> completed = row("COMPLETED", OffsetDateTime.now().minusHours(2));
        when(db.booking(booking)).thenReturn(completed);
        when(db.lockBooking(booking)).thenReturn(completed);
        String comment = "x".repeat(2000);

        service.rate(booking, new ConsultationRatingRequest(4, comment));

        verify(db).rate(argThat(values -> comment.equals(values.get("comment"))));
    }

    @Test
    void candidateCanCancelPendingBookingAndReplayCannotRestoreAnotherCredit() {
        Map<String, Object> pending = row("PENDING", OffsetDateTime.now().plusDays(1));
        when(db.booking(booking)).thenReturn(pending);
        when(db.lockBooking(booking)).thenReturn(pending);

        service.cancel(booking);
        pending.put("status", "CANCELLED");
        pending.put("credit_refunded", true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.cancel(booking)).status());

        verify(db, times(1)).refundCredit(credit);
        verify(db, times(1)).end(booking, "CANCELLED", true);
        verify(db, times(1)).closeThread(thread);
    }

    @Test
    void approvalRechecksOwnershipAfterAcquiringBookingLock() {
        Map<String, Object> before = row("PENDING", OffsetDateTime.now().plusDays(1));
        Map<String, Object> reassigned = row("PENDING", OffsetDateTime.now().plusDays(1));
        reassigned.put("consultant_user_id", UUID.randomUUID());
        when(db.booking(booking)).thenReturn(before);
        when(db.lockBooking(booking)).thenReturn(reassigned);
        login(consultant, "CAREER_CONSULTANT");

        assertEquals(404, assertThrows(ApiException.class, () -> service.accept(booking)).status());

        verify(db).lockPeople(candidate, consultant);
        verify(db, never()).accept(any());
        verify(db, never()).inform(any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void manualCompletionReplayDoesNotRepeatSettlementOrRatingNotice() {
        Map<String, Object> delivered = row("ACCEPTED", OffsetDateTime.now().minusHours(2));
        delivered.put("meeting_status", "ENDED");
        delivered.put("meeting_opened_at", OffsetDateTime.now().minusHours(2));
        when(db.booking(booking)).thenReturn(delivered);
        when(db.lockBooking(booking)).thenReturn(delivered);
        login(consultant, "CAREER_CONSULTANT");

        service.complete(booking);
        delivered.put("status", "COMPLETED");
        service.complete(booking);

        verify(db, times(1)).end(booking, "COMPLETED", false);
        verify(db, times(1))
            .inform(eq(candidate), eq(thread), eq("CONSULTATION_RATING_REQUEST"), anyString(), anyString());
        verify(db, never()).refundCredit(any());
    }

    @Test
    void pastOrAbsentStartIsRejectedBeforeAnyCreditLookup() {
        for (OffsetDateTime start : new OffsetDateTime[]{null, OffsetDateTime.now().minusSeconds(1)}) {
            ConsultationBookingRequest request = new ConsultationBookingRequest(
                consultant,
                credit,
                "BE",
                "CV review",
                "Career direction",
                start,
                60,
                UUID.randomUUID(),
                null
            );
            assertEquals(400, assertThrows(ApiException.class, () -> service.create(request)).status());
        }

        verifyNoInteractions(db);
    }

    @Test
    void asynchronousProductCannotSupplyLiveBookingCredit() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        when(db.consultant(consultant)).thenReturn(Map.of("target_roles", List.of("BE")));
        when(db.credit(credit, candidate)).thenReturn(
            fields("status", "PAID", "is_session_pack", true, "delivery_mode", "ASYNC", "sessions_remaining", 1)
        );

        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.create(
                    new ConsultationBookingRequest(
                        consultant,
                        credit,
                        "BE",
                        "CV review",
                        "Career direction",
                        start,
                        60,
                        UUID.randomUUID(),
                        null
                    )
                )
            ).status()
        );

        verify(db, never()).reserveCredit(any());
        verify(db, never()).createThread(anyMap());
        verify(db, never()).insert(anyMap());
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
            start.plusMinutes(60),
            "subject",
            "CV review",
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
