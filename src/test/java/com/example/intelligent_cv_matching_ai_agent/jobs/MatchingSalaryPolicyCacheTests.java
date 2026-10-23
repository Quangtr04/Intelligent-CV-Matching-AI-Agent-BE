package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchingSalaryPolicyCacheTests {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void policyChangesBypassBothCachePathsIncludingPreviouslyCachedEmptyRuns(
        boolean aiEnabled,
        boolean redisEnabled) {
        Fixture fixture = new Fixture(aiEnabled, redisEnabled);
        var first = fixture.match();
        var repeated = fixture.match();
        assertEquals(first.get("id"), repeated.get("id"));
        assertEquals(true, repeated.get("cached"));
        assertEquals(redisEnabled ? "REDIS" : "DATABASE", repeated.get("cache_source"));
        assertPolicy(first, "10", 1);
        verify(fixture.db, never()).insertJobMatchResults(anyMap());

        fixture.settings.set(policy("20", 2));
        var changed = fixture.match();
        var changedRepeated = fixture.match();
        assertNotEquals(first.get("id"), changed.get("id"));
        assertEquals(false, changed.get("cached"));
        assertEquals(changed.get("id"), changedRepeated.get("id"));
        assertEquals(true, changedRepeated.get("cached"));
        assertPolicy(changed, "20", 2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
        verify(fixture.db).insertJobMatchResults(result.capture());
        Map<?, ?> reason = (Map<?, ?>) result.getValue().get("match_reason_json");
        Map<?, ?> salary = (Map<?, ?>) reason.get("salary_match");
        assertEquals(2L, salary.get("settings_version"));
        assertEquals(new BigDecimal("6000000.0000"), salary.get("minimum_acceptable"));

        // Returning to ten percent still has a new settings version: the old empty run must not be reused.
        fixture.settings.set(policy("10", 3));
        var reverted = fixture.match();
        assertNotEquals(first.get("id"), reverted.get("id"));
        assertNotEquals(changed.get("id"), reverted.get("id"));
        assertPolicy(reverted, "10", 3);
        assertEquals(reverted.get("id"), fixture.match().get("id"));
        assertPolicy(fixture.service.result((UUID) first.get("id")), "10", 1);
        verify(fixture.policies, times(6)).effective();
        verify(fixture.db, times(3)).insertJobMatchingRuns(anyMap());
        if (aiEnabled) {
            verify(fixture.ai).embed(anyString());
            verify(fixture.ai).generate(eq("JOB_MATCH_RERANK"), anyMap());
        } else {
            verify(fixture.ai, never()).embed(anyString());
            verify(fixture.ai, never()).generate(anyString(), anyMap());
        }
        if (redisEnabled) {
            assertEquals(fixture.redisLookups.get(0), fixture.redisLookups.get(1));
            assertEquals(fixture.redisLookups.get(2), fixture.redisLookups.get(3));
            assertEquals(fixture.redisLookups.get(4), fixture.redisLookups.get(5));
            assertEquals(3, fixture.redisLookups.stream().distinct().count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aConcurrentSettingsChangeDoesNotMixPolicyVersionsInsideOneRun(
        boolean aiEnabled) {
        Fixture fixture = new Fixture(aiEnabled, false);
        when(fixture.db.listActiveJobSalaryMetadata()).thenAnswer(ignored -> {
            fixture.settings.set(policy("20", 2));
            return fixture.catalog();
        });
        var run = fixture.match();
        assertPolicy(run, "10", 1);
        assertEquals(0, ((Map<?, ?>) run.get("salary_filter")).get("eligible_jobs"));
        verify(fixture.policies).effective();
        verify(fixture.db, never()).insertJobMatchResults(anyMap());
        verify(fixture.ai, never()).embed(anyString());
    }

    private static MatchingSalaryPolicy policy(
        String tolerance,
        long version) {
        return new MatchingSalaryPolicy(new BigDecimal(tolerance), version, null, null);
    }

    private static void assertPolicy(
        Map<String, Object> run,
        String tolerance,
        long version) {
        Map<?, ?> policy = (Map<?, ?>) run.get("salary_filter");
        assertEquals(0, new BigDecimal(tolerance).compareTo((BigDecimal) policy.get("tolerance_percent")));
        assertEquals(version, policy.get("settings_version"));
    }

    private static final class Fixture {

        final MatchingRepository db = mock(MatchingRepository.class);

        final CurrentUser user = mock(CurrentUser.class);

        final AiService ai = mock(AiService.class);

        final MatchingSalaryPolicyService policies = mock(MatchingSalaryPolicyService.class);

        final AtomicReference<MatchingSalaryPolicy> settings = new AtomicReference<>(policy("10", 1));

        final UUID owner = UUID.randomUUID();

        final UUID job = UUID.randomUUID();

        final List<String> redisLookups = new ArrayList<>();

        final MatchingService service;

        @SuppressWarnings("unchecked")
        Fixture(
            boolean aiEnabled,
            boolean redisEnabled) {
            when(user.id()).thenReturn(owner);
            when(policies.effective()).thenAnswer(ignored -> settings.get());
            when(ai.enabled()).thenReturn(aiEnabled);
            when(ai.provider()).thenReturn("ollama");
            when(ai.model()).thenReturn("test-chat");
            when(ai.embeddingModel()).thenReturn("test-embedding");
            float[] vector = new float[1024];
            vector[0] = 1;
            when(ai.embed(anyString())).thenReturn(vector);
            when(ai.generate(eq("JOB_MATCH_RERANK"), anyMap())).thenReturn(
                Map.of("results", List.of(Map.of("job_id", job.toString(), "score", 0.9, "reason", "Java match")))
            );
            when(db.findCandidateProfilesByUserId(owner)).thenReturn(
                java.util.Optional
                    .of(Map.of("expected_salary", 7500000, "salary_currency", "VND", "profile_summary", "Java"))
            );
            when(db.listActiveJobSalaryMetadata()).thenAnswer(ignored -> catalog());
            when(db.listMarketJobSkillsByIds(anyString())).thenAnswer(ignored -> jobs());
            when(db.listMarketJobEmbeddingsByStatusForIds(any(), any(), any(), any(), any(), any()))
                .thenAnswer(ignored -> jobs());
            when(db.readMarketJobPostsByStatus()).thenReturn("stable-job-catalog");
            when(db.readMarketJobEmbeddingsByStatus(any(), any())).thenReturn("stable-embeddings");
            when(db.json(any())).thenAnswer(call -> call.getArgument(0));
            Map<String, UUID> hashes = new HashMap<>();
            Map<UUID, Map<String, Object>> runs = new HashMap<>();
            when(db.findJobMatchingRunsByUserId(eq(owner), anyString())).thenAnswer(call -> {
                UUID id = hashes.get(call.getArgument(1, String.class));
                return id == null ? java.util.Optional.empty() : java.util.Optional.of(Map.of("id", id));
            });
            when(db.insertJobMatchingRuns(anyMap())).thenAnswer(call -> {
                Map<String, Object> values = call.getArgument(0);
                Map<String, Object> snapshot = (Map<String, Object>) values.get("profile_snapshot");
                UUID id = UUID.randomUUID();
                hashes.put(snapshot.get("request_hash").toString(), id);
                runs.put(id, Map.of("id", id, "profile_snapshot", snapshot));
                return id;
            });
            when(db.getJobMatchingRunsById(any(), eq(owner)))
                .thenAnswer(call -> new LinkedHashMap<>(runs.get(call.getArgument(0, UUID.class))));
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            if (redisEnabled) {
                StringRedisTemplate template = mock(StringRedisTemplate.class);
                ValueOperations<String, String> values = mock(ValueOperations.class);
                Map<String, String> cached = new HashMap<>();
                when(redis.getIfAvailable()).thenReturn(template);
                when(template.opsForValue()).thenReturn(values);
                when(values.get(anyString())).thenAnswer(call -> {
                    String key = call.getArgument(0);
                    redisLookups.add(key);
                    return cached.get(key);
                });
                doAnswer(call -> {
                    cached.put(call.getArgument(0), call.getArgument(1));
                    return null;
                }).when(values).set(anyString(), anyString(), any(Duration.class));
            }
            service = new MatchingService(db, user, ai, mock(ResumeService.class), redis, policies);
        }

        List<Map<String, Object>> catalog() {
            return List.of(Map.of("id", job, "salary_text", "6 million VND/month"));
        }

        List<Map<String, Object>> jobs() {
            return List.of(
                new LinkedHashMap<>(
                    Map.of(
                        "id",
                        job,
                        "salary_text",
                        "6 million VND/month",
                        "skills",
                        List.of(Map.of("name", "java", "required", true)),
                        "vector_score",
                        0.9
                    )
                )
            );
        }

        Map<String, Object> match() {
            return service.match("CAREER_PROFILE", List.of(), 10);
        }
    }
}
