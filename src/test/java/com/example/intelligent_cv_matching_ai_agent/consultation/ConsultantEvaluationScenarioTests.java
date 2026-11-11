package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAtsRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.resume.ResumeWritingTestData;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultantEvaluationService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit boundaries for assigned Consultant evaluation; all providers and stores are mocked. */
class ConsultantEvaluationScenarioTests {

    private final ConsultationEvaluationRepository db = mock(ConsultationEvaluationRepository.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final UUID consultant = UUID.randomUUID();

    private final UUID candidate = UUID.randomUUID();

    private final UUID consultation = UUID.randomUUID();

    private final UUID resume = UUID.randomUUID();

    private final UUID job = UUID.randomUUID();

    private final ConsultantEvaluationService service =
        new ConsultantEvaluationService(db, new CurrentUser(), ai, resumes);

    private final Map<String, Object> cv = Map.of(
        "id",
        resume,
        "user_id",
        candidate,
        "status",
        "READY",
        "language",
        "en",
        "structured_data",
        Map.of("summary", "Reviewed Java experience")
    );

    @BeforeEach
    void setup() {
        login("CAREER_CONSULTANT");
        when(db.assignedThread(consultation, consultant))
            .thenReturn(Map.of("candidate_user_id", candidate, "status", "OPEN"));
        when(db.readyResume(resume, candidate)).thenReturn(cv);
        when(db.activeJob(job)).thenReturn(Map.of("id", job, "job_title", "Java Developer"));
        when(db.jobSkills(job)).thenReturn(List.of(Map.of("skill_name", "Java", "is_required", true)));
        when(db.findAtsAnalysis(eq(candidate), eq(resume), eq(job), anyString())).thenReturn(Optional.empty());
        when(db.json(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resumes.canonicalText(cv)).thenReturn("Reviewed Java experience");
        when(ai.enabled()).thenReturn(true);
        when(ai.provider()).thenReturn("MOCK");
        when(ai.model()).thenReturn("mock-model");
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assignedConsultantListsOnlyTheAssignedCandidatesReadyResumes() {
        List<Map<String, Object>> available = List.of(cv);
        when(db.listReadyResumes(candidate)).thenReturn(available);

        assertSame(available, service.candidateResumes(consultation));

        verify(db).assignedThread(consultation, consultant);
        verify(db).listReadyResumes(candidate);
        verifyNoInteractions(ai, resumes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "MANAGER", "ADMIN"})
    void otherRolesCannotListOrEvaluateCandidateDocuments(
        String role) {
        login(role);

        assertEquals(403, assertThrows(ApiException.class, () -> service.candidateResumes(consultation)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.analyzeResume(consultation, resume)).status());
        assertEquals(
            403,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verifyNoInteractions(db, ai, resumes);
    }

    @Test
    void unassignedConsultantIsDeniedBeforeCandidateContentOrProviderAccess() {
        when(db.assignedThread(consultation, consultant)).thenThrow(ApiException.notFound("Consultation not found"));

        assertEquals(404, assertThrows(ApiException.class, () -> service.analyzeResume(consultation, resume)).status());
        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verify(db, never()).readyResume(any(), any());
        verify(db, never()).activeJob(any());
        verifyNoInteractions(ai, resumes);
    }

    @Test
    void closedConsultationCannotSpendAiOnANewEvaluation() {
        when(db.assignedThread(consultation, consultant))
            .thenReturn(Map.of("candidate_user_id", candidate, "status", "CLOSED"));

        assertEquals(409, assertThrows(ApiException.class, () -> service.analyzeResume(consultation, resume)).status());
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verify(db, never()).readyResume(any(), any());
        verifyNoInteractions(ai, resumes);
    }

    @Test
    void foreignOrUnreadyCvCannotReachAiOrAResultCache() {
        when(db.readyResume(resume, candidate)).thenThrow(ApiException.notFound("Resume not found"));

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verify(db, never()).findAtsAnalysis(any(), any(), any(), anyString());
        verify(db, never()).activeJob(any());
        verifyNoInteractions(ai, resumes);
    }

    @Test
    void inactiveJobCannotReachAiEvenWithAReadyCv() {
        when(db.activeJob(job)).thenThrow(ApiException.notFound("Job not found"));

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verify(db, never()).insertAtsAnalysis(anyMap());
        verifyNoInteractions(ai);
    }

    @Test
    void atsCacheHitRevalidatesAssignmentAndBothResourcesWithoutAnotherInferenceOrAudit() {
        Map<String, Object> cached = Map.of("id", UUID.randomUUID(), "ats_score", 82);
        when(db.findAtsAnalysis(eq(candidate), eq(resume), eq(job), anyString())).thenReturn(Optional.of(cached));

        Map<String, Object> result = service.ats(consultation, job, new ConsultationAtsRequest(resume));

        assertEquals(true, result.get("cached"));
        assertEquals(82, result.get("ats_score"));
        var order = inOrder(db);
        order.verify(db).assignedThread(consultation, consultant);
        order.verify(db).readyResume(resume, candidate);
        order.verify(db).activeJob(job);
        order.verify(db).jobSkills(job);
        order.verify(db).findAtsAnalysis(eq(candidate), eq(resume), eq(job), anyString());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(db, never()).insertAtsAnalysis(anyMap());
        verify(db, never()).insertAudit(anyMap());
    }

    @Test
    void atsPersistsCandidateOwnershipWhileAuditingTheActualConsultant() {
        UUID analysis = UUID.randomUUID();
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(validAts());
        when(db.insertAtsAnalysis(anyMap())).thenReturn(analysis);
        when(db.atsAnalysis(analysis, candidate, resume, job)).thenReturn(Map.of("id", analysis, "ats_score", 82));

        Map<String, Object> result = service.ats(consultation, job, new ConsultationAtsRequest(resume));

        assertEquals(false, result.get("cached"));
        assertEquals(analysis, result.get("id"));
        verify(ai).generate(
            eq("JOB_ATS"),
            argThat(
                input -> "Reviewed Java experience".equals(input.get("resume_text"))
                    && "en".equals(input.get("language"))
            )
        );
        verify(db).insertAtsAnalysis(
            argThat(
                fields -> candidate.equals(fields.get("user_id")) && resume.equals(fields.get("resume_id"))
                    && job.equals(fields.get("market_job_id")) && Double.valueOf(82).equals(fields.get("ats_score"))
            )
        );
        verify(db).insertAudit(
            argThat(
                fields -> consultant.equals(fields.get("actor_user_id")) && consultation.equals(fields.get("entity_id"))
                    && "CONSULTATION_JOB_ATS".equals(fields.get("action"))
            )
        );
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 101, Double.NaN, Double.POSITIVE_INFINITY})
    void malformedAtsScoreCannotPersistAnAnalysisOrAnAudit(
        double score) {
        Map<String, Object> invalid = new LinkedHashMap<>(validAts());
        invalid.put("ats_score", score);
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(invalid);

        assertEquals(
            502,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );

        verify(db, never()).insertAtsAnalysis(anyMap());
        verify(db, never()).insertAudit(anyMap());
    }

    @Test
    void disabledAiOrProviderQuotaFailureCannotPublishAnEmptyEvaluation() {
        when(ai.enabled()).thenReturn(false);
        assertEquals(
            503,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );
        verify(ai, never()).generate(anyString(), anyMap());
        when(ai.enabled()).thenReturn(true);
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenThrow(new ApiException(429, "Quota exceeded"));
        assertEquals(
            429,
            assertThrows(ApiException.class, () -> service.ats(consultation, job, new ConsultationAtsRequest(resume)))
                .status()
        );
        verify(db, never()).insertAtsAnalysis(anyMap());
        verify(db, never()).insertAudit(anyMap());
    }

    @Test
    void generalCvReviewStoresAQualityAnalysisWithoutInferringAJobFitScore() {
        UUID analysis = UUID.randomUUID();
        when(ai.generate(eq("CV_ANALYZE"), anyMap())).thenReturn(ResumeWritingTestData.report());
        when(db.insertResumeAnalysis(anyMap())).thenReturn(analysis);
        when(db.resumeAnalysis(analysis, resume)).thenReturn(Map.of("id", analysis, "resume_id", resume));

        assertEquals(analysis, service.analyzeResume(consultation, resume).get("id"));

        verify(db).insertResumeAnalysis(
            argThat(
                fields -> resume.equals(fields.get("resume_id")) && "AI_QUALITY".equals(fields.get("analysis_type"))
                    && !fields.containsKey("market_job_id")
            )
        );
        verify(db, never()).activeJob(any());
        verify(db, never()).insertAtsAnalysis(anyMap());
        verify(db).insertAudit(
            argThat(
                fields -> consultant.equals(fields.get("actor_user_id"))
                    && "CONSULTATION_CV_ANALYZE".equals(fields.get("action"))
            )
        );
    }

    private Map<String, Object> validAts() {
        return Map.of(
            "ats_score",
            82,
            "strengths",
            List.of("Java experience"),
            "missing_skills",
            List.of("Docker evidence missing"),
            "improvement_suggestions",
            List.of("Add truthful project evidence")
        );
    }

    private void login(
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                consultant.toString(),
                "",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}
