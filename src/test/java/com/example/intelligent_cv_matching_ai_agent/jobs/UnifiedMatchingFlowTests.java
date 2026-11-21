package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.*;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@SuppressWarnings("unchecked")
class UnifiedMatchingFlowTests {

    private final MatchingRepository db = mock(MatchingRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final AiService ai = mock(AiService.class);

    private final RequirementEvidenceService evidence = mock(RequirementEvidenceService.class);

    private final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);

    private final UUID owner = UUID.randomUUID(), cvId = UUID.randomUUID(), jobId = UUID.randomUUID(),
        runId = UUID.randomUUID();

    private final Map<String, Object> cv = fields(
        "id",
        cvId,
        "user_id",
        owner,
        "status",
        "READY",
        "structured_data",
        fields("skills", List.of(fields("name", "Java")))
    );

    private final Map<String, Object> job = fields(
        "id",
        jobId,
        "job_title",
        "Backend",
        "salary_text",
        "Negotiable",
        "parsed_requirements",
        "Java",
        "skills",
        List.of(fields("name", "Java", "required", true))
    );

    private final Map<String, Object> report = fields(
        "version",
        "requirement-evidence-v1",
        "score",
        0.5,
        "input_hash",
        "pair-hash",
        "requirements",
        List.of(
            fields(
                "text",
                "Java",
                "importance",
                "MANDATORY",
                "status",
                "PARTIAL",
                "reason",
                "Some conditions need evidence",
                "skills",
                List.of("Java"),
                "evidence",
                List.of(fields("source", "resume:" + cvId + "/skills/0", "quote", "Java"))
            )
        ),
        "strengths",
        List.of(),
        "skill_gaps",
        List.of("Java"),
        "requirement_coverage_available",
        true
    );

    private final Map<String, Object> run = new LinkedHashMap<>();

    private final List<Map<String, Object>> results = new ArrayList<>();

    private MatchingService service;

    @BeforeEach
    void setup() {
        when(user.id()).thenReturn(owner);
        when(resumes.owned(cvId)).thenReturn(cv);
        when(resumes.canonicalText(cv)).thenReturn("Java");
        when(db.listMarketJobSkillsByMarketJobId()).thenReturn(List.of(job));
        when(db.listMarketJobSkillsByIds(anyString())).thenAnswer(call -> List.of(new LinkedHashMap<>(job)));
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(db.readMarketJobPostsByStatus()).thenReturn("catalog-v1");
        when(db.insertJobMatchingRuns(anyMap())).thenAnswer(call -> {
            run.putAll(call.getArgument(0));
            run.put("id", runId);
            run.put("completed_at", OffsetDateTime.now());
            return runId;
        });
        when(db.getJobMatchingRunsById(runId, owner)).thenAnswer(call -> new LinkedHashMap<>(run));
        when(db.listJobMatchResultsByMatchingRunId(runId)).thenReturn(results);
        when(db.insertJobMatchResults(anyMap())).thenAnswer(call -> {
            results.add(new LinkedHashMap<>(call.getArgument(0)));
            return UUID.randomUUID();
        });
        doAnswer(call -> {
            object(run.get("profile_snapshot"))
                .put("evidence_status", call.getArgument(1, Boolean.class) ? "DEFERRED" : "VERIFIED");
            return null;
        }).when(db).updateEvidenceStatus(eq(runId), anyBoolean());
        when(evidence.enabled()).thenReturn(true);
        var prepared = new RequirementEvidenceService(
            mock(
                com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.RequirementEvidenceRepository.class
            ),
            ai,
            user
        ).candidate(List.of(cv), Map.of(), List.of());
        when(evidence.candidate(anyList(), anyMap(), anyList())).thenReturn(prepared);
        when(evidence.assess(eq(prepared), anyMap())).thenReturn(report);
        var policies = mock(MatchingSalaryPolicyService.class);
        when(policies.effective()).thenReturn(MatchingSalaryPolicy.legacyDefault());
        service = new MatchingService(db, user, ai, resumes, redis, policies, null, evidence);
    }

    @Test
    void detailedTargetAssessmentUsesTheRequirementLedgerWithoutCatalogReranking() {
        service.match("SELECTED_CV", List.of(cvId), 1, "DETAILED", jobId);
        assertEquals(0.5, results.getFirst().get("final_score"));
        var reason = object(results.getFirst().get("match_reason_json"));
        assertEquals("REQUIREMENT_EVIDENCE", reason.get("method"));
        assertEquals(report, reason.get("assessment"));
        assertNull(results.getFirst().get("llm_rerank_score"));
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(db, never()).listMarketJobEmbeddingsByStatus(any(), any(), any(), any(), any());
    }

    @Test
    void selectedJobIsPassedToTheBackendAndDoesNotSearchTheCatalog() {
        service.match("SELECTED_CV", List.of(cvId), 1, "DETAILED", jobId);
        verify(db, never()).listMarketJobSkillsByMarketJobId();
        verify(db, never()).readMarketJobPostsByStatus();
        verify(db, times(2)).listMarketJobSkillsByIds("{" + jobId + "}");
        assertEquals(jobId, results.getFirst().get("market_job_id"));
    }

    @Test
    void salaryPreferenceDoesNotHideAnExplicitlySelectedJobFromEvidenceAnalysis() {
        when(db.findCandidateProfilesByUserId(owner))
            .thenReturn(Optional.of(fields("expected_salary", 100000000, "salary_currency", "VND")));
        job.put("salary_text", "1000000 VND/month");
        service.match("SELECTED_CV", List.of(cvId), 1, "DETAILED", jobId);
        assertEquals(1, results.size());
        assertEquals(0.5, results.getFirst().get("final_score"));
    }

    @Test
    void deferredProviderVerificationStillReturnsRecommendationsAndMarksTheRunAsUncached() {
        report.put("verification_status", "DEFERRED");
        report.put("coverage_complete", false);
        var response = service.match("SELECTED_CV", List.of(cvId), 1, "DETAILED", jobId);
        assertEquals(1, array(response.get("results")).size());
        assertEquals("DEFERRED", object(response.get("profile_snapshot")).get("evidence_status"));
        verify(db).updateEvidenceStatus(runId, true);
    }

    @Test
    void selectedPairAnalysisPersistsTheJobAtsScoreAndSuggestions() {
        var careerDb = mock(CareerToolsRepository.class);
        when(careerDb.getMarketJobPostsById(jobId)).thenReturn(job);
        when(careerDb.json(any())).thenAnswer(call -> call.getArgument(0));
        Map<String, Object> saved = new LinkedHashMap<>();
        UUID id = UUID.randomUUID();
        when(careerDb.insertJobResumeAnalyses(anyMap())).thenAnswer(call -> {
            saved.putAll(call.getArgument(0));
            saved.put("id", id);
            return id;
        });
        when(careerDb.getJobResumeAnalysesById(id)).thenAnswer(call -> new LinkedHashMap<>(saved));
        var suggestions = List.of("Add a concrete Java project example.");
        when(ai.generate(eq("JOB_ATS"), anyMap()))
            .thenReturn(fields("ats_score", 72.0, "improvement_suggestions", suggestions));
        var tools = new CareerToolsService(careerDb, user, service, resumes, ai, redis);
        var response = tools.ats(jobId, new CvRequest(cvId));
        assertEquals(72.0, saved.get("ats_score"));
        assertEquals(72.0, response.get("ats_score"));
        assertEquals(suggestions, response.get("improvement_suggestions_json"));
        assertEquals(false, response.get("cached"));
        verify(ai).generate(eq("JOB_ATS"), anyMap());
    }
}
