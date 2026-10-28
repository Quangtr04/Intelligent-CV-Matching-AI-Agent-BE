package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchingRecommendationFallbackTests {

    @ParameterizedTest
    @ValueSource(strings = {"CAREER_PROFILE", "SELECTED_CV"})
    void missingJobVectorsReturnsKnownAndUndisclosedSalaryCandidatesWithAnHonestRuleBasedMode(
        String basis) {
        Fixture fixture = new Fixture();
        var run = fixture.service.match(basis, basis.equals("SELECTED_CV") ? List.of(fixture.cv) : List.of(), 10);

        assertEquals("RULE_BASED", run.get("provider"));
        assertEquals("skill-overlap-v1", run.get("model"));
        Map<?, ?> snapshot = (Map<?, ?>) run.get("profile_snapshot");
        assertEquals("SKILL_OVERLAP_UNINDEXED_JOBS", snapshot.get("mode"));
        verify(fixture.db)
            .updateJobMatchingRunsMode(fixture.run, "SKILL_OVERLAP_UNINDEXED_JOBS", "RULE_BASED", "skill-overlap-v1");
        assertEquals(3, fixture.savedResults.size());
        for (Map<String, Object> saved : fixture.savedResults) {
            assertNull(saved.get("llm_rerank_score"));
            assertNull(saved.get("vector_similarity_score"));
            Map<?, ?> reason = (Map<?, ?>) saved.get("match_reason_json");
            assertEquals("SKILL_OVERLAP_UNINDEXED_JOBS", reason.get("method"));
        }
        Map<?, ?> unknownReason = (Map<?, ?>) fixture.result(fixture.javaUnknown).get("match_reason_json");
        Map<?, ?> unknownSalary = (Map<?, ?>) unknownReason.get("salary_match");
        assertEquals(false, unknownSalary.get("verified"));
        assertEquals("UNVERIFIED", unknownSalary.get("status"));
        Map<?, ?> knownReason = (Map<?, ?>) fixture.result(fixture.javaKnown).get("match_reason_json");
        assertEquals(true, ((Map<?, ?>) knownReason.get("salary_match")).get("verified"));
        verify(fixture.ai, never()).embed(anyString());
        verify(fixture.ai, never()).generate(anyString(), anyMap());
        verify(fixture.db, never()).listMarketJobEmbeddingsByStatusForIds(any(), any(), any(), any(), any(), any());

        if (basis.equals("SELECTED_CV")) {
            assertEquals(1.0, ((Number) fixture.result(fixture.javaKnown).get("final_score")).doubleValue());
            assertEquals(1.0, ((Number) fixture.result(fixture.javaUnknown).get("final_score")).doubleValue());
            assertEquals(0.0, ((Number) fixture.result(fixture.python).get("final_score")).doubleValue());
            verify(fixture.resumes).canonicalText(fixture.resume);
            verify(fixture.db, never()).listCandidateProfileSkillsByUserId(fixture.owner);
            assertTrue(snapshot.get("candidate").toString().contains("Java reviewed CV"));
            assertFalse(snapshot.get("candidate").toString().contains("Python career objective"));
        } else {
            assertEquals(0.0, ((Number) fixture.result(fixture.javaKnown).get("final_score")).doubleValue());
            assertEquals(1.0, ((Number) fixture.result(fixture.python).get("final_score")).doubleValue());
            verify(fixture.resumes, never()).canonicalText(anyMap());
            verify(fixture.db).listCandidateProfileSkillsByUserId(fixture.owner);
        }
    }

    @Test
    void partiallyIndexedCatalogReranksRelevantUnindexedCvJobsTogetherWithVectorCandidates() {
        Fixture fixture = new Fixture();
        when(fixture.db.readMarketJobEmbeddingsByStatus(any(), any())).thenReturn("one-current-vector");
        Map<String, Object> indexed =
            fixture.jobs().stream().filter(job -> fixture.python.equals(job.get("id"))).findFirst().orElseThrow();
        indexed.put("vector_score", 0.99);
        when(fixture.db.listMarketJobEmbeddingsByStatusForIds(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(indexed));
        when(fixture.ai.generate(eq("JOB_MATCH_RERANK"), anyMap())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(1);
            List<?> jobs = (List<?>) input.get("jobs");
            assertEquals(
                Set.of(fixture.python, fixture.javaKnown, fixture.javaUnknown),
                jobs.stream().map(job -> ((Map<?, ?>) job).get("id")).collect(java.util.stream.Collectors.toSet())
            );
            assertTrue(input.get("candidate").toString().contains("Java reviewed CV"));
            assertFalse(input.get("candidate").toString().contains("Python career objective"));
            return Map.of(
                "results",
                List.of(
                    Map.of("job_id", fixture.javaKnown.toString(), "score", 0.95, "reason", "Reviewed Java experience"),
                    Map.of(
                        "job_id",
                        fixture.javaUnknown.toString(),
                        "score",
                        0.85,
                        "reason",
                        "Java role; salary unknown"
                    ),
                    Map.of("job_id", fixture.python.toString(), "score", 0.1, "reason", "No reviewed Python experience")
                )
            );
        });

        var run = fixture.service.match("SELECTED_CV", List.of(fixture.cv), 10);
        assertEquals("ollama", run.get("provider"));
        assertEquals("SEMANTIC_LLM", ((Map<?, ?>) run.get("profile_snapshot")).get("mode"));
        assertEquals(3, fixture.savedResults.size());
        assertEquals(1, fixture.result(fixture.javaKnown).get("rank_position"));
        assertEquals(0.95, ((Number) fixture.result(fixture.javaKnown).get("llm_rerank_score")).doubleValue());
        assertEquals(3, fixture.result(fixture.python).get("rank_position"));
        assertEquals(0.99, ((Number) fixture.result(fixture.python).get("vector_similarity_score")).doubleValue());
        Map<?, ?> javaReason = (Map<?, ?>) fixture.result(fixture.javaKnown).get("match_reason_json");
        Map<?, ?> pythonReason = (Map<?, ?>) fixture.result(fixture.python).get("match_reason_json");
        assertEquals("SKILL", javaReason.get("retrieval"));
        assertEquals("VECTOR", pythonReason.get("retrieval"));
        for (var saved : fixture.savedResults) {
            assertNotNull(saved.get("llm_rerank_score"));
        }
        verify(fixture.ai).embed(anyString());
        verify(fixture.ai).generate(eq("JOB_MATCH_RERANK"), anyMap());
        verify(fixture.db, never()).updateJobMatchingRunsMode(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aResumeOutsideTheCurrentAccountIsStillRejectedBeforeMatching() {
        Fixture fixture = new Fixture();
        when(fixture.resumes.owned(fixture.cv)).thenThrow(ApiException.notFound("CV not found"));
        ApiException error =
            assertThrows(ApiException.class, () -> fixture.service.match("SELECTED_CV", List.of(fixture.cv), 10));
        assertEquals(404, error.status());
        verify(fixture.db, never()).listActiveJobSalaryMetadata();
        verify(fixture.db, never()).insertJobMatchingRuns(anyMap());
        verify(fixture.ai, never()).embed(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "UPLOADED", "PARSING"})
    void unconfirmedResumesDoNotEnterTheFallback(
        String status) {
        Fixture fixture = new Fixture();
        when(fixture.resumes.owned(fixture.cv)).thenReturn(Map.of("id", fixture.cv, "status", status));
        ApiException error =
            assertThrows(ApiException.class, () -> fixture.service.match("SELECTED_CV", List.of(fixture.cv), 10));
        assertEquals(409, error.status());
        verify(fixture.resumes, never()).canonicalText(anyMap());
        verify(fixture.db, never()).insertJobMatchingRuns(anyMap());
        verify(fixture.ai, never()).embed(anyString());
    }

    private static final class Fixture {

        final UUID owner = UUID.randomUUID();

        final UUID cv = UUID.randomUUID();

        final UUID run = UUID.randomUUID();

        final UUID javaKnown = UUID.randomUUID();

        final UUID javaUnknown = UUID.randomUUID();

        final UUID python = UUID.randomUUID();

        final MatchingRepository db = mock(MatchingRepository.class);

        final CurrentUser user = mock(CurrentUser.class);

        final AiService ai = mock(AiService.class);

        final ResumeService resumes = mock(ResumeService.class);

        final Map<String, Object> resume = Map.of("id", cv, "status", "READY");

        final List<Map<String, Object>> savedResults = new ArrayList<>();

        final MatchingService service;

        @SuppressWarnings("unchecked")
        Fixture() {
            when(user.id()).thenReturn(owner);
            when(ai.enabled()).thenReturn(true);
            when(ai.provider()).thenReturn("ollama");
            when(ai.model()).thenReturn("test-chat");
            when(ai.embeddingModel()).thenReturn("test-embedding");
            float[] vector = new float[1024];
            vector[0] = 1;
            when(ai.embed(anyString())).thenReturn(vector);
            when(resumes.owned(cv)).thenReturn(resume);
            when(resumes.canonicalText(resume)).thenReturn("Java reviewed CV\nJava backend development experience");
            when(db.findCandidateProfilesByUserId(owner)).thenReturn(
                Optional.of(
                    fields(
                        "expected_salary",
                        5000000,
                        "salary_currency",
                        "VND",
                        "target_job_title",
                        "Python Developer",
                        "profile_summary",
                        "Python career objective"
                    )
                )
            );
            when(db.listCandidateProfileSkillsByUserId(owner)).thenReturn(List.of(Map.of("name", "python")));
            when(db.listActiveJobSalaryMetadata()).thenAnswer(ignored -> jobs());
            when(db.listMarketJobSkillsByIds(anyString())).thenAnswer(ignored -> jobs());
            when(db.listMarketJobEmbeddingsByStatusForIds(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
            when(db.readMarketJobPostsByStatus()).thenReturn("test-job-catalog");
            when(db.readMarketJobEmbeddingsByStatus(any(), any())).thenReturn("");
            when(db.findJobMatchingRunsByUserId(any(), anyString())).thenReturn(Optional.empty());
            when(db.json(any())).thenAnswer(call -> call.getArgument(0));
            AtomicReference<Map<String, Object>> storedRun = new AtomicReference<>();
            when(db.insertJobMatchingRuns(anyMap())).thenAnswer(call -> {
                Map<String, Object> values = new LinkedHashMap<>(call.getArgument(0));
                values.put("id", run);
                storedRun.set(values);
                return run;
            });
            when(db.updateJobMatchingRunsMode(eq(run), anyString(), anyString(), anyString())).thenAnswer(call -> {
                Map<String, Object> values = storedRun.get();
                values.put("provider", call.getArgument(2));
                values.put("model", call.getArgument(3));
                ((Map<String, Object>) values.get("profile_snapshot")).put("mode", call.getArgument(1));
                return 1;
            });
            when(db.insertJobMatchResults(anyMap())).thenAnswer(call -> {
                savedResults.add(new LinkedHashMap<>(call.getArgument(0)));
                return UUID.randomUUID();
            });
            when(db.getJobMatchingRunsById(run, owner)).thenAnswer(ignored -> new LinkedHashMap<>(storedRun.get()));
            when(db.listJobMatchResultsByMatchingRunId(run)).thenReturn(savedResults);
            MatchingSalaryPolicyService policies = mock(MatchingSalaryPolicyService.class);
            when(policies.effective()).thenReturn(MatchingSalaryPolicy.legacyDefault());
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            service = new MatchingService(db, user, ai, resumes, redis, policies);
        }

        List<Map<String, Object>> jobs() {
            return List.of(
                job(javaKnown, "7000000 VND/month", "java"),
                job(javaUnknown, "Negotiable", "java"),
                job(python, "20000000 VND/month", "python")
            );
        }

        private Map<String, Object> job(
            UUID id,
            String salary,
            String skill) {
            return fields(
                "id",
                id,
                "job_title",
                skill + " Developer",
                "raw_content",
                "A job requiring " + skill,
                "salary_text",
                salary,
                "skills",
                List.of(Map.of("name", skill, "required", true))
            );
        }

        Map<String, Object> result(
            UUID job) {
            return savedResults.stream().filter(value -> job.equals(value.get("market_job_id"))).findFirst()
                .orElseThrow();
        }
    }
}
