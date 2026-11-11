package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAdviceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationPrivateNotesRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAdviceRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAdviceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationAdviceScenarioTests {

    private final ConsultationAdviceRepository db = mock(ConsultationAdviceRepository.class);

    private final ConsultationEvaluationRepository evaluations = mock(ConsultationEvaluationRepository.class);

    private final ConsultationAdviceService service = new ConsultationAdviceService(db, evaluations, new CurrentUser());

    private final UUID thread = UUID.randomUUID();

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultant = UUID.randomUUID();

    @BeforeEach
    void setup() {
        login(consultant, "CAREER_CONSULTANT");
        when(db.context(thread, consultant, true)).thenReturn(
            fields(
                "candidate_user_id",
                candidate,
                "request_type",
                "SESSION_BOOKING",
                "booking_status",
                "ACCEPTED",
                "credit_refunded",
                false
            )
        );
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void invalidAdviceVersionCannotPersistAuditOrNotify(
        Long version) {
        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.saveAdvice(thread, new ConsultationAdviceRequest(version, "DRAFT", null, null, Map.of()))
        );

        assertEquals(400, failure.status());
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
        verify(db, never()).audit(any(), any(), anyString(), anyLong());
        verify(db, never()).publishedNotification(any(), any());
    }

    private static Stream<Long> invalidVersions() {
        return Stream.of(null, -1L, Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "published", "CLOSED"})
    void unsupportedAdviceStatusCannotLoadOrSaveReport(
        String status) {
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, status, null, null, Map.of()))
            ).status()
        );

        verify(db, never()).advice(any());
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void legacyConsultationRejectsForeignOrUnreadyResumeEvenInDraftAdvice() {
        when(db.context(thread, consultant, true))
            .thenReturn(fields("candidate_user_id", candidate, "request_type", "DIRECT_CHAT", "status", "OPEN"));
        UUID resume = UUID.randomUUID();
        when(evaluations.readyResume(resume, candidate)).thenThrow(ApiException.notFound("Resume not found"));

        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", resume, null, Map.of()))
            ).status()
        );

        verify(evaluations).readyResume(resume, candidate);
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
        verify(db, never()).audit(any(), any(), anyString(), anyLong());
    }

    @Test
    void expiredJobPreventsLegacyPublicationWithoutAnyWriteOrNotification() {
        when(db.context(thread, consultant, true))
            .thenReturn(fields("candidate_user_id", candidate, "request_type", "DIRECT_CHAT", "status", "OPEN"));
        UUID job = UUID.randomUUID();
        when(evaluations.activeJob(job)).thenThrow(ApiException.notFound("Job not found"));

        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "PUBLISHED", null, job, Map.of()))
            ).status()
        );

        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
        verify(db, never()).publishedNotification(any(), any());
        verify(evaluations).activeJob(job);
    }

    @Test
    void candidateAndManagerCannotReadPrivateNotesOrWriteReports() {
        for (String role : List.of("CANDIDATE", "MANAGER")) {
            login(candidate, role);
            assertEquals(403, assertThrows(ApiException.class, () -> service.privateNotes(thread)).status());
            assertEquals(
                403,
                assertThrows(
                    ApiException.class,
                    () -> service.savePrivateNotes(thread, new ConsultationPrivateNotesRequest(0L, "Private", null))
                ).status()
            );
            assertEquals(
                403,
                assertThrows(
                    ApiException.class,
                    () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", null, null, Map.of()))
                ).status()
            );
        }

        verifyNoInteractions(evaluations);
        verify(db, never()).context(any(), any(), anyBoolean());
        verify(db, never()).privateNotes(any(), any());
        verify(db, never()).advice(any());
    }

    @Test
    void deniedCandidateContextCannotRevealEvenPublishedReport() {
        login(candidate, "CANDIDATE");
        when(db.context(thread, candidate, false)).thenThrow(ApiException.notFound("Consultation not found"));

        assertEquals(404, assertThrows(ApiException.class, () -> service.advice(thread)).status());

        verify(db, never()).advice(any());
    }

    @Test
    void managerCannotReadSharedAdviceByKnowingThreadId() {
        login(candidate, "MANAGER");

        assertEquals(403, assertThrows(ApiException.class, () -> service.advice(thread)).status());

        verifyNoInteractions(db);
    }

    @Test
    void tenThousandCharacterPrivateNoteAndRubricEndpointsArePreserved() {
        String note = "n".repeat(10000);
        when(db.savePrivateNotes(eq(thread), eq(consultant), eq(note), anyMap(), eq(0L))).thenAnswer(
            invocation -> fields(
                "thread_id",
                thread,
                "consultant_user_id",
                consultant,
                "notes",
                invocation.getArgument(2),
                "rubric_json",
                invocation.getArgument(3),
                "version",
                1L
            )
        );

        Map<String, Object> result = service.savePrivateNotes(
            thread,
            new ConsultationPrivateNotesRequest(0L, note, new ConsultationPrivateNotesRequest.Rubric(0, 10, 0, 10))
        );

        assertEquals(note, result.get("notes"));
        assertEquals(
            Map.of("cvQuality", 0, "technicalDepth", 10, "communication", 0, "marketReadiness", 10),
            result.get("rubric")
        );
        assertEquals(1L, result.get("version"));
        verify(db).audit(thread, consultant, "CONSULTATION_PRIVATE_NOTES_SAVE", 1L);
        verify(db, never()).publishedNotification(any(), any());
    }

    @Test
    void oversizePrivateNoteIsRejectedBeforeVersionLookupOrWrite() {
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.savePrivateNotes(thread, new ConsultationPrivateNotesRequest(0L, "n".repeat(10001), null))
            ).status()
        );

        verify(db, never()).privateNotes(any(), any());
        verify(db, never()).savePrivateNotes(any(), any(), anyString(), anyMap(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 11})
    void eachRubricFieldMustStayWithinZeroAndTen(
        int score) {
        List<ConsultationPrivateNotesRequest.Rubric> rubrics = List.of(
            new ConsultationPrivateNotesRequest.Rubric(score, 5, 5, 5),
            new ConsultationPrivateNotesRequest.Rubric(5, score, 5, 5),
            new ConsultationPrivateNotesRequest.Rubric(5, 5, score, 5),
            new ConsultationPrivateNotesRequest.Rubric(5, 5, 5, score)
        );
        for (ConsultationPrivateNotesRequest.Rubric rubric : rubrics) {
            assertEquals(
                400,
                assertThrows(
                    ApiException.class,
                    () -> service.savePrivateNotes(thread, new ConsultationPrivateNotesRequest(0L, "Private", rubric))
                ).status()
            );
        }

        verify(db, never()).savePrivateNotes(any(), any(), anyString(), anyMap(), anyLong());
        verify(db, never()).audit(any(), any(), anyString(), anyLong());
    }

    @Test
    void emptyPrivateNoteResultDoesNotExposeSharedReportOrCreateAnything() {
        when(db.context(thread, consultant, false)).thenReturn(Map.of());
        when(db.privateNotes(thread, consultant)).thenReturn(Optional.empty());

        Map<String, Object> response = service.privateNotes(thread);

        assertEquals("", response.get("notes"));
        assertEquals(Map.of(), response.get("rubric"));
        assertEquals(0L, response.get("version"));
        assertEquals(consultant, response.get("consultantId"));
        assertFalse(response.containsKey("advice"));
        verify(db, never()).advice(any());
        verify(db, never()).savePrivateNotes(any(), any(), anyString(), anyMap(), anyLong());
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
