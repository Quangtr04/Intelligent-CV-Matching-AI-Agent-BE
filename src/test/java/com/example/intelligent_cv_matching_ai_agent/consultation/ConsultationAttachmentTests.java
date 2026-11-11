package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAnalysisRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAtsRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAnalysisRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationPreparationService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAnalysisService;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultantEvaluationService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationAttachmentTests {

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultant = UUID.randomUUID();

    private final UUID booking = UUID.randomUUID();

    private final UUID thread = UUID.randomUUID();

    private final UUID resume = UUID.randomUUID();

    private final UUID job = UUID.randomUUID();

    private final ConsultationBookingRepository db = mock(ConsultationBookingRepository.class);

    private final ConsultationAnalysisRepository reports = mock(ConsultationAnalysisRepository.class);

    private final CurrentUser user = new CurrentUser();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingOrForeignCvAndInvalidJobAreRejectedBeforeReservingCredit() {
        login(candidate, "CANDIDATE");
        var service = new ConsultationBookingService(db, user);
        UUID credit = UUID.randomUUID();
        assertEquals(400, assertThrows(ApiException.class, () -> service.create(request(credit, null, null))).status());
        doThrow(ApiException.notFound("CV unavailable")).when(db).requireReadyResume(resume, candidate);
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.create(request(credit, resume, null))).status()
        );
        doNothing().when(db).requireReadyResume(resume, candidate);
        doThrow(ApiException.notFound("Job unavailable")).when(db).requireActiveJob(job);
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.create(request(credit, resume, job))).status()
        );
        verify(db, never()).reserveCredit(any());
        verify(db, never()).credit(any(), any());
        verify(db, never()).createThread(any());
    }

    @Test
    void preparationChecksParticipantsBeforeReadingAnyAssets() {
        when(db.booking(booking)).thenReturn(row("PENDING", false));
        login(UUID.randomUUID(), "CAREER_CONSULTANT");
        var service = new ConsultationPreparationService(db, reports, user);
        assertEquals(404, assertThrows(ApiException.class, () -> service.preparation(booking)).status());
        verify(db, never()).selectedResume(any(), any());
        verify(db, never()).selectedJob(any());
        verifyNoInteractions(reports);
    }

    @Test
    void deletedCandidateCannotShareARetainedReadyCv() {
        when(db.booking(booking)).thenReturn(row("ACCEPTED", false));
        doThrow(ApiException.notFound("Candidate unavailable")).when(db).requireLiveCandidate(candidate);
        login(consultant, "CAREER_CONSULTANT");
        var service = new ConsultationPreparationService(db, reports, user);
        assertEquals(404, assertThrows(ApiException.class, () -> service.preparation(booking)).status());
        verify(db, never()).selectedResume(any(), any());
        verify(db, never()).selectedJob(any());
        verifyNoInteractions(reports);
    }

    @Test
    void cancelledOrRefundedBookingRevokesConsultantButKeepsCandidateHistory() {
        var service = new ConsultationPreparationService(db, reports, user);
        for (var state : List.of(row("CANCELLED", false), row("DECLINED", true), row("COMPLETED", true))) {
            state.put("resume_id", null);
            state.put("job_id", null);
            when(db.booking(booking)).thenReturn(state);
            login(consultant, "CAREER_CONSULTANT");
            assertEquals(404, assertThrows(ApiException.class, () -> service.preparation(booking)).status());
            login(candidate, "CANDIDATE");
            assertTrue(service.preparation(booking).values().stream().allMatch(Objects::isNull));
        }
    }

    @Test
    void legacyUnattachedBookingReturnsNullsWithoutQueryingLatestCvOrReports() {
        var row = row("COMPLETED", false);
        row.put("resume_id", null);
        row.put("job_id", null);
        when(db.booking(booking)).thenReturn(row);
        login(consultant, "CAREER_CONSULTANT");
        var result = new ConsultationPreparationService(db, reports, user).preparation(booking);
        assertEquals(Set.of("resume", "job", "resume_analysis", "ats_analysis"), result.keySet());
        assertTrue(result.values().stream().allMatch(Objects::isNull));
        verifyNoInteractions(reports);
        verify(db, never()).selectedResume(any(), any());
    }

    @Test
    void noSelectedJobNeverBroadensAtsLookupToOtherJobs() {
        var row = row("PENDING", false);
        row.put("job_id", null);
        when(db.booking(booking)).thenReturn(row);
        when(db.selectedResume(resume, candidate)).thenReturn(Optional.of(Map.of("id", resume)));
        login(candidate, "CANDIDATE");
        var result = new ConsultationPreparationService(db, reports, user).preparation(booking);
        assertNull(result.get("job"));
        assertNull(result.get("ats_analysis"));
        verify(reports, never()).listAtsAnalyses(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void sessionReportsUseOnlySelectedPairAndDoNotReadOtherCandidateHistories() {
        login(consultant, "CAREER_CONSULTANT");
        var consultations = mock(ConsultationRepository.class);
        when(reports.candidateIdForThread(thread, consultant)).thenReturn(candidate);
        when(consultations.getConsultationThreadsById(thread)).thenReturn(context());
        when(consultations.bookingForThread(thread)).thenReturn(Map.of("status", "ACCEPTED", "credit_refunded", false));
        var service = new ConsultationAnalysisService(reports, consultations, user);
        var result = service.candidateAnalyses(thread, new ConsultationAnalysisRequest(null, null, null, null));
        assertEquals(resume, result.resumeId());
        assertEquals(job, result.jobId());
        assertTrue(result.matchingResults().isEmpty());
        verify(reports).listResumeAnalyses(candidate, resume, 20, 0);
        verify(reports).listAtsAnalyses(candidate, resume, job, 20, 0);
        verify(reports, never()).listMatchingResults(any(), any(), any(), anyInt(), anyInt());
        verify(reports, never()).listSalaryEstimations(any(), any(), anyInt(), anyInt());
        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service
                    .candidateAnalyses(thread, new ConsultationAnalysisRequest(UUID.randomUUID(), job, null, null))
            ).status()
        );
    }

    @Test
    void candidateProfileCannotSelectAnotherCvOrUseAnImplicitPrimaryCv() {
        login(consultant, "CAREER_CONSULTANT");
        var consultations = mock(ConsultationRepository.class);
        when(consultations.getConsultationThreadsById(thread)).thenReturn(context());
        when(consultations.bookingForThread(thread)).thenReturn(Map.of("status", "PENDING", "credit_refunded", false));
        when(consultations.findCandidateProfilesByUserId(candidate)).thenReturn(Optional.empty());
        when(consultations.getResumesById(resume, candidate)).thenReturn(Map.of("id", resume));
        var service = new ConsultationService(consultations, user);
        assertEquals(Map.of("id", resume), service.candidateProfile(thread, null).get("resume"));
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.candidateProfile(thread, UUID.randomUUID())).status()
        );
        verify(consultations, never()).findResumesByUserId(any());
    }

    @Test
    void mismatchedAtsPairCannotReachCacheOrAiAndPendingBookingCannotChargeAi() {
        login(consultant, "CAREER_CONSULTANT");
        var evaluations = mock(ConsultationEvaluationRepository.class);
        var ai = mock(AiService.class);
        var resumes = mock(ResumeService.class);
        var context = context();
        when(evaluations.assignedThread(thread, consultant)).thenReturn(context);
        var service = new ConsultantEvaluationService(evaluations, user, ai, resumes);
        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.ats(thread, UUID.randomUUID(), new ConsultationAtsRequest(resume))
            ).status()
        );
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.analyzeResume(thread, UUID.randomUUID())).status()
        );
        context.put("booking_status", "PENDING");
        assertEquals(409, assertThrows(ApiException.class, () -> service.analyzeResume(thread, resume)).status());
        verify(evaluations, never()).findAtsAnalysis(any(), any(), any(), anyString());
        verifyNoInteractions(ai, resumes);
    }

    private Map<String, Object> context() {
        return fields(
            "candidate_user_id",
            candidate,
            "consultant_user_id",
            consultant,
            "request_type",
            "SESSION_BOOKING",
            "resume_id",
            resume,
            "job_id",
            job,
            "status",
            "OPEN",
            "booking_status",
            "ACCEPTED",
            "credit_refunded",
            false
        );
    }

    private Map<String, Object> row(
        String status,
        boolean refunded) {
        return fields(
            "candidate_user_id",
            candidate,
            "consultant_user_id",
            consultant,
            "status",
            status,
            "credit_refunded",
            refunded,
            "resume_id",
            resume,
            "job_id",
            job
        );
    }

    private ConsultationBookingRequest request(
        UUID credit,
        UUID selectedResume,
        UUID selectedJob) {
        return new ConsultationBookingRequest(
            consultant,
            credit,
            "BE",
            "CV review",
            "Prepare together",
            OffsetDateTime.now().plusDays(1),
            60,
            selectedResume,
            selectedJob
        );
    }

    private void login(
        UUID id,
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                id.toString(),
                "test",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}
