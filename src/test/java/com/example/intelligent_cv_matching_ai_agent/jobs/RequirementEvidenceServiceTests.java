package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.RequirementEvidenceRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.RequirementEvidenceService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

class RequirementEvidenceServiceTests {

    private final RequirementEvidenceRepository db = mock(RequirementEvidenceRepository.class);

    private final AiService ai = mock(AiService.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final UUID owner = UUID.randomUUID();

    private final UUID resumeId = UUID.randomUUID();

    private final UUID jobId = UUID.randomUUID();

    private final Map<String, Map<String, Object>> reports = new HashMap<>();

    private final Map<String, float[]> vectors = new HashMap<>();

    private final RequirementEvidenceService service = new RequirementEvidenceService(db, ai, user);

    @BeforeEach
    void setup() {
        when(user.id()).thenReturn(owner);
        when(ai.enabled()).thenReturn(true);
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(ai.provider()).thenReturn("gemini");
        when(ai.embeddingModel()).thenReturn("gemini-embedding-001");
        when(ai.cacheFingerprint(RequirementEvidence.FEATURE)).thenReturn("verifier-v1");
        when(db.report(eq(owner), anyString()))
            .thenAnswer(call -> Optional.ofNullable(reports.get(call.getArgument(1))));
        doAnswer(call -> {
            reports.put(call.getArgument(1), new LinkedHashMap<>(call.getArgument(2)));
            return null;
        }).when(db).saveReport(eq(owner), anyString(), anyMap());
        when(db.vector(eq(owner), anyString(), anyString(), anyString()))
            .thenAnswer(call -> Optional.ofNullable(vectors.get(call.getArgument(3))));
        doAnswer(call -> {
            vectors.put(call.getArgument(3), call.getArgument(4));
            return null;
        }).when(db).saveVector(eq(owner), anyString(), anyString(), anyString(), any(float[].class));
        when(ai.embed(anyString())).thenAnswer(call -> {
            float[] vector = new float[1024];
            vector[0] = 1;
            return vector;
        });
        respond("MET", false);
    }

    @Test
    void highSimilarityAloneNeverEarnsCoverage() {
        respond("MISSING_EVIDENCE", false);
        var result = service.assess(candidate("Deployed on Azure"), job("Experience deploying on AWS"));
        assertEquals(0.0, result.get("score"));
        verify(ai, atLeastOnce()).embed(anyString());
        assertEquals("MISSING_EVIDENCE", object(array(result.get("requirements")).getFirst()).get("status"));
    }

    @Test
    void aModelCannotSubstituteAzureForAnExplicitAwsRequirementEvenWithARealQuote() {
        var result = service.assess(candidate("Deployed on Azure"), job("Experience deploying on AWS"));
        assertEquals(0.0, result.get("score"));
        assertEquals("UNVERIFIED", object(array(result.get("requirements")).getFirst()).get("status"));
    }

    @Test
    void sourceReportIsSharedAcrossListAndDirectPairAndInvalidatesAfterCvOrPromptChanges() {
        var first = service.assess(candidate("Built HTTP endpoints"), job("Experience building APIs"));
        var direct = service.assess(candidate("Built HTTP endpoints"), job("Experience building APIs"));
        assertEquals(first, direct);
        verify(ai, times(1)).generate(eq(RequirementEvidence.FEATURE), anyMap());
        service.assess(candidate("Built HTTP endpoints and tested requests"), job("Experience building APIs"));
        verify(ai, times(2)).generate(eq(RequirementEvidence.FEATURE), anyMap());
        when(ai.cacheFingerprint(RequirementEvidence.FEATURE)).thenReturn("verifier-v2");
        service.assess(candidate("Built HTTP endpoints and tested requests"), job("Experience building APIs"));
        verify(ai, times(3)).generate(eq(RequirementEvidence.FEATURE), anyMap());
    }

    @Test
    void commonRequirementVerdictsAreReusedAcrossJobsAndCvVectorsAreNotRegenerated() {
        var candidate = candidate("Built HTTP endpoints");
        service.assess(candidate, job("Experience building APIs"));
        var anotherJob = job("Experience building APIs");
        anotherJob.put("id", UUID.randomUUID());
        service.assess(candidate, anotherJob);
        verify(ai, times(1)).generate(eq(RequirementEvidence.FEATURE), anyMap());
        verify(ai, times(2)).embed(anyString());
    }

    @Test
    void simpleSkillDeclarationUsesTheSameLedgerWithoutASecondScoreOrPaidGeneration() {
        var candidate = service.candidate(
            List.of(
                fields(
                    "id",
                    resumeId,
                    "status",
                    "READY",
                    "user_id",
                    owner,
                    "structured_data",
                    fields("skills", List.of(fields("name", "Postgres")))
                )
            ),
            Map.of(),
            List.of()
        );
        var target = fields("id", jobId, "job_title", "Backend", "parsed_requirements", "PostgreSQL");
        var result = service.assess(candidate, target);
        assertEquals(1.0, result.get("score"));
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void hallucinatedEvidenceFailsWithoutCachingAPartialReport() {
        respond("MET", true);
        var report = service.assess(candidate("Built endpoints"), job("Experience building APIs"));
        assertEquals("DEFERRED", report.get("verification_status"));
        assertEquals(0.0, report.get("score"));
        assertTrue(array(report.get("skill_gaps")).isEmpty());
        verify(db, never()).saveReport(any(), anyString(), anyMap());
    }

    @Test
    void transientVerificationFailureKeepsJobsAndStopsProviderCallsForTheRestOfTheRequest() {
        when(ai.generate(eq(RequirementEvidence.FEATURE), anyMap()))
            .thenThrow(new ApiException(503, "AI provider temporarily unavailable"));
        var prepared = candidate("Built HTTP endpoints");
        var first = service.assess(prepared, job("Experience building APIs"));
        var second = service.assess(prepared, job("Experience deploying services"));
        for (var report : List.of(first, second)) {
            assertEquals("DEFERRED", report.get("verification_status"));
            assertEquals(false, report.get("requirement_coverage_available"));
            assertEquals(false, report.get("coverage_complete"));
            assertTrue(array(report.get("skill_gaps")).isEmpty());
        }
        verify(ai, times(1)).generate(eq(RequirementEvidence.FEATURE), anyMap());
        verify(ai, times(2)).embed(anyString());
        verify(db, never()).saveReport(any(), anyString(), anyMap());
        respond("MET", false);
        var recovered = service.assess(candidate("Built HTTP endpoints"), job("Experience building APIs"));
        assertEquals("VERIFIED", recovered.get("verification_status"));
        assertEquals(1.0, recovered.get("score"));
        verify(ai, times(2)).generate(eq(RequirementEvidence.FEATURE), anyMap());
    }

    @Test
    void confirmedDeclarationsRemainAvailableWhenAnotherRequirementCannotBeVerified() {
        when(ai.generate(eq(RequirementEvidence.FEATURE), anyMap()))
            .thenThrow(new ApiException(503, "AI provider temporarily unavailable"));
        var prepared = service.candidate(
            List.of(
                fields(
                    "id",
                    resumeId,
                    "status",
                    "READY",
                    "user_id",
                    owner,
                    "structured_data",
                    fields(
                        "skills",
                        List.of(fields("name", "Java")),
                        "projects",
                        List.of(fields("description", "Built endpoints"))
                    )
                )
            ),
            Map.of(),
            List.of()
        );
        var report = service.assess(prepared, job("Java\nExperience building APIs"));
        assertEquals("DEFERRED", report.get("verification_status"));
        assertEquals(0.5, report.get("score"));
        assertEquals(true, report.get("requirement_coverage_available"));
        assertEquals(List.of("Java"), report.get("strengths"));
        assertTrue(array(report.get("skill_gaps")).isEmpty());
    }

    @Test
    void embeddingFailureIsAlsoDeferredButQuotaAndAccessFailuresRemainErrors() {
        when(ai.embed(anyString())).thenThrow(new ApiException(502, "AI embedding failed"));
        var report = service.assess(candidate("Built endpoints"), job("Experience building APIs"));
        assertEquals("DEFERRED", report.get("verification_status"));
        verify(ai, never()).generate(anyString(), anyMap());
        doThrow(new ApiException(429, "Quota exhausted")).when(ai).embed(anyString());
        assertEquals(
            429,
            assertThrows(
                ApiException.class,
                () -> service.assess(candidate("Built endpoints"), job("Experience building APIs"))
            ).status()
        );
    }

    @Test
    void embeddingCallBudgetReturnsAPendingReportBeforeCallingTheProviderAgain() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxEmbeddingCalls", 1);
        var report = service.assess(candidate("Built HTTP endpoints"), job("Experience building APIs"));
        assertEquals("DEFERRED", report.get("verification_status"));
        assertEquals("BUDGET", report.get("verification_deferred_reason"));
        verify(ai, times(1)).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(db, never()).saveReport(any(), anyString(), anyMap());
    }

    @Test
    void verificationBudgetKeepsCompletedBatchesAndDoesNotCacheAnUnfinishedReport() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxVerificationCalls", 1);
        String requirements =
            java.util.stream.IntStream.range(0, 7).mapToObj(index -> "Experience building APIs for module " + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        var report = service.assess(candidate("Built HTTP endpoints"), job(requirements));
        assertEquals("DEFERRED", report.get("verification_status"));
        assertEquals("BUDGET", report.get("verification_deferred_reason"));
        assertEquals(6.0 / 7, report.get("score"));
        verify(ai, times(1)).generate(eq(RequirementEvidence.FEATURE), anyMap());
        assertTrue(reports.values().stream().allMatch(row -> row.containsKey("verdict")));
    }

    @Test
    void foreignAndUnconfirmedCvsAreRejectedBeforePaidCalls() {
        assertThrows(
            ApiException.class,
            () -> service.candidate(
                List.of(fields("id", resumeId, "status", "READY", "user_id", UUID.randomUUID())),
                Map.of(),
                List.of()
            )
        );
        assertThrows(
            ApiException.class,
            () -> service
                .candidate(List.of(fields("id", resumeId, "status", "DRAFT", "user_id", owner)), Map.of(), List.of())
        );
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void emptyEvidenceDoesNotFabricateQualificationsOrSpendEmbeddingQuota() {
        var candidate = service.candidate(
            List.of(fields("id", resumeId, "status", "READY", "structured_data", Map.of())),
            Map.of(),
            List.of()
        );
        var result = service.assess(candidate, job("Experience building APIs"));
        assertEquals(0.0, result.get("score"));
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    private RequirementEvidenceService.Candidate candidate(
        String description) {
        return service.candidate(
            List.of(
                fields(
                    "id",
                    resumeId,
                    "status",
                    "READY",
                    "user_id",
                    owner,
                    "structured_data",
                    fields("projects", List.of(Map.of("description", description)))
                )
            ),
            Map.of(),
            List.of()
        );
    }

    private Map<String, Object> job(
        String requirement) {
        return fields("id", jobId, "job_title", "Backend Developer", "parsed_requirements", requirement);
    }

    private void respond(
        String status,
        boolean hallucinate) {
        when(ai.generate(eq(RequirementEvidence.FEATURE), anyMap())).thenAnswer(call -> {
            var input = object(call.getArgument(1));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object raw : array(input.get("requirements"))) {
                var requirement = object(raw);
                var chunk = object(array(requirement.get("candidates")).getFirst());
                rows.add(
                    fields(
                        "requirement_id",
                        requirement.get("requirement_id"),
                        "status",
                        status,
                        "reason",
                        "A source-bound assessment",
                        "evidence",
                        status.equals("MISSING_EVIDENCE")
                            ? List.of()
                            : List.of(
                                fields(
                                    "chunk_id",
                                    chunk.get("chunk_id"),
                                    "quote",
                                    hallucinate ? "INVENTED CLAIM" : chunk.get("text")
                                )
                            )
                    )
                );
            }
            return Map.of("results", rows);
        });
    }
}
