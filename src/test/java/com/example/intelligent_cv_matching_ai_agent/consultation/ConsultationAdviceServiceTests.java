package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAdviceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationPrivateNotesRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAdviceRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAdviceService;
import com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationAdviceContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationAdviceServiceTests {

    private final ConsultationAdviceRepository db = mock(ConsultationAdviceRepository.class);

    private final ConsultationEvaluationRepository evaluations = mock(ConsultationEvaluationRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ConsultationAdviceService service = new ConsultationAdviceService(db, evaluations, user);

    private final UUID thread = UUID.randomUUID();

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultant = UUID.randomUUID();

    private Map<String, Object> context;

    @BeforeEach
    void setup() {
        when(user.id()).thenReturn(consultant);
        when(user.hasRole("CAREER_CONSULTANT")).thenReturn(true);
        context = fields(
            "candidate_user_id",
            candidate,
            "request_type",
            "SESSION_BOOKING",
            "status",
            "OPEN",
            "booking_status",
            "ACCEPTED",
            "credit_refunded",
            false
        );
        when(db.context(thread, consultant, true)).thenReturn(context);
        when(db.context(thread, consultant, false)).thenReturn(context);
        when(db.advice(thread)).thenReturn(Optional.empty());
        when(db.privateNotes(thread, consultant)).thenReturn(Optional.empty());
    }

    @Test
    void candidateCannotDiscoverDraftContentOrItsVersion() {
        when(user.hasRole("CAREER_CONSULTANT")).thenReturn(false);
        when(user.hasRole("CANDIDATE")).thenReturn(true);
        when(db.advice(thread)).thenReturn(Optional.of(report("DRAFT", 7)));
        Map<String, Object> response = service.advice(thread);
        assertEquals("EMPTY", response.get("status"));
        assertEquals(0L, response.get("version"));
        assertNull(response.get("advice"));
        assertFalse(response.containsKey("resumeId"));
    }

    @Test
    void candidateReceivesPublishedAdviceWithServerOwnedAuthor() {
        when(user.hasRole("CAREER_CONSULTANT")).thenReturn(false);
        when(user.hasRole("CANDIDATE")).thenReturn(true);
        when(db.advice(thread)).thenReturn(Optional.of(report("PUBLISHED", 2)));
        Map<String, Object> response = service.advice(thread);
        assertEquals("PUBLISHED", response.get("status"));
        assertEquals(2L, response.get("version"));
        assertEquals("Assigned Consultant", ((Map<?, ?>) response.get("advice")).get("consultantName"));
        assertFalse(response.containsKey("notes"));
        assertFalse(response.containsKey("rubric"));
    }

    @Test
    void assignedConsultantCanReviewTheirDraft() {
        when(db.advice(thread)).thenReturn(Optional.of(report("DRAFT", 3)));
        assertEquals("DRAFT", service.advice(thread).get("status"));
    }

    @Test
    void publicationChecksReferencesAndNotifiesCandidateWithoutChat() {
        UUID resume = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        context.put("resume_id", resume);
        context.put("job_id", job);
        Map<String, Object> saved = report("PUBLISHED", 1);
        when(
            db.saveAdvice(
                eq(thread),
                eq(candidate),
                eq(consultant),
                eq(resume),
                eq(job),
                eq("PUBLISHED"),
                anyMap(),
                eq(0L)
            )
        ).thenReturn(saved);
        Map<String, Object> response =
            service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "PUBLISHED", resume, job, content()));
        assertEquals("PUBLISHED", response.get("status"));
        verify(evaluations).readyResume(resume, candidate);
        verify(evaluations, never()).activeJob(any());
        verify(db).publishedNotification(thread, candidate);
        verify(db).audit(thread, consultant, "CONSULTATION_ADVICE_PUBLISHED", 1L);
    }

    @Test
    void sessionReportCannotChangeTheCandidatesAttachedCvOrJob() {
        context.put("resume_id", UUID.randomUUID());
        context.put("job_id", UUID.randomUUID());
        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service
                    .saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", UUID.randomUUID(), null, content()))
            ).status()
        );
        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service
                    .saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", null, UUID.randomUUID(), content()))
            ).status()
        );
        verifyNoInteractions(evaluations);
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void draftDoesNotSendPublicationNotification() {
        when(
            db.saveAdvice(eq(thread), eq(candidate), eq(consultant), isNull(), isNull(), eq("DRAFT"), anyMap(), eq(0L))
        ).thenReturn(report("DRAFT", 1));
        assertEquals(
            "DRAFT",
            service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", null, null, content())).get("status")
        );
        verify(db, never()).publishedNotification(any(), any());
    }

    @Test
    void staleVersionCannotOverwriteNewerReport() {
        when(db.advice(thread)).thenReturn(Optional.of(report("DRAFT", 3)));
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(2L, "DRAFT", null, null, content()))
            ).status()
        );
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void publishedReportCannotBeHiddenAsDraft() {
        when(db.advice(thread)).thenReturn(Optional.of(report("PUBLISHED", 3)));
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(3L, "DRAFT", null, null, content()))
            ).status()
        );
    }

    @Test
    void onlyAcceptedOrCompletedUnrefundedBookingsCanReceiveAdvice() {
        for (String status : List.of("PENDING", "CANCELLED", "DECLINED", "EXPIRED")) {
            context.put("booking_status", status);
            assertEquals(
                409,
                assertThrows(
                    ApiException.class,
                    () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", null, null, content()))
                ).status()
            );
        }
        context.put("booking_status", "ACCEPTED");
        context.put("credit_refunded", true);
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "DRAFT", null, null, content()))
            ).status()
        );
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void completedBookingCanReceivePostSessionReport() {
        context.put("booking_status", "COMPLETED");
        context.put("status", "CLOSED");
        when(
            db.saveAdvice(
                eq(thread),
                eq(candidate),
                eq(consultant),
                isNull(),
                isNull(),
                eq("PUBLISHED"),
                anyMap(),
                eq(0L)
            )
        ).thenReturn(report("PUBLISHED", 1));
        assertEquals(
            "PUBLISHED",
            service.saveAdvice(thread, new ConsultationAdviceRequest(0L, "PUBLISHED", null, null, content()))
                .get("status")
        );
    }

    @Test
    void privateNotesUseSeparateVersionedStoreAndNeverPublish() {
        Map<String, Object> saved = fields(
            "thread_id",
            thread,
            "consultant_user_id",
            consultant,
            "notes",
            "Private observation",
            "rubric_json",
            Map.of("cvQuality", 7),
            "version",
            1L
        );
        when(db.savePrivateNotes(eq(thread), eq(consultant), eq("Private observation"), anyMap(), eq(0L)))
            .thenReturn(saved);
        Map<String, Object> response = service.savePrivateNotes(
            thread,
            new ConsultationPrivateNotesRequest(
                0L,
                "  Private observation  ",
                new ConsultationPrivateNotesRequest.Rubric(7, 6, 8, 7)
            )
        );
        assertEquals("Private observation", response.get("notes"));
        verify(db, never()).publishedNotification(any(), any());
        verify(db).audit(thread, consultant, "CONSULTATION_PRIVATE_NOTES_SAVE", 1L);
        verify(db, never()).saveAdvice(any(), any(), any(), any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void invalidRubricAndStaleNotesAreRejectedBeforePersistence() {
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.savePrivateNotes(
                    thread,
                    new ConsultationPrivateNotesRequest(
                        0L,
                        "Note",
                        new ConsultationPrivateNotesRequest.Rubric(11, 6, 8, 7)
                    )
                )
            ).status()
        );
        when(db.privateNotes(thread, consultant)).thenReturn(Optional.of(Map.of("version", 2L)));
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.savePrivateNotes(thread, new ConsultationPrivateNotesRequest(1L, "Note", null))
            ).status()
        );
        verify(db, never()).savePrivateNotes(any(), any(), any(), anyMap(), anyLong());
    }

    @Test
    void contentRejectsMissingApplicationAdviceWhenPublishing() {
        Map<String, Object> advice = new LinkedHashMap<>(content());
        advice.remove("applicationAdvice");
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> ConsultationAdviceContent.normalize(advice, true)).status()
        );
    }

    @Test
    void contentRejectsUnknownFieldsHugeListsAndInvertedSalaryRange() {
        Map<String, Object> advice = new LinkedHashMap<>(content());
        advice.put("privateNotes", "Secret");
        Map<String, Object> unsupported = advice;
        assertThrows(ApiException.class, () -> ConsultationAdviceContent.normalize(unsupported, false));
        advice = new LinkedHashMap<>(content());
        advice.put("cvAdvice", Map.of("actionItems", java.util.Collections.nCopies(51, "Too many")));
        Map<String, Object> huge = advice;
        assertThrows(ApiException.class, () -> ConsultationAdviceContent.normalize(huge, false));
        Map<String, Object> inverted = new LinkedHashMap<>(content());
        inverted.put("salaryAdvice", Map.of("minSalary", 100, "targetSalary", 10, "maxSalary", 20));
        assertThrows(ApiException.class, () -> ConsultationAdviceContent.normalize(inverted, false));
    }

    @Test
    void clientCannotForgeAuthorOrUpdatedTime() {
        Map<String, Object> advice = new LinkedHashMap<>(content());
        advice.put("consultantName", "Fake author");
        advice.put("lastUpdated", "Fake time");
        Map<String, Object> normalized = ConsultationAdviceContent.normalize(advice, true);
        assertFalse(normalized.containsKey("consultantName"));
        assertFalse(normalized.containsKey("lastUpdated"));
    }

    private Map<String, Object> report(
        String status,
        long version) {
        return fields(
            "thread_id",
            thread,
            "candidate_user_id",
            candidate,
            "consultant_user_id",
            consultant,
            "consultant_name",
            "Assigned Consultant",
            "status",
            status,
            "version",
            version,
            "advice_json",
            content()
        );
    }

    private Map<String, Object> content() {
        return Map.of(
            "cvAdvice",
            Map.of("summary", "Quantify project outcomes"),
            "careerAdvice",
            Map.of("recommendedPositioning", "Apply for junior backend roles"),
            "applicationAdvice",
            Map.of("summary", "Tailor the cover letter to the position")
        );
    }
}
