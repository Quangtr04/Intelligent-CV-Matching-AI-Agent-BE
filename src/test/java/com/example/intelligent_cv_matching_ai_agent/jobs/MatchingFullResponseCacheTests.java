package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.MatchingSalaryPolicy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchingFullResponseCacheTests {

    @Test
    void redisHitReturnsFullResultsWithoutReadingRunOrResultRows() {
        Fixture fixture = new Fixture();
        Map<String, Object> first = fixture.match();
        clearInvocations(fixture.db, fixture.ai);

        Map<String, Object> repeated = fixture.match();

        assertEquals(first.get("id").toString(), repeated.get("id").toString());
        assertEquals(true, repeated.get("cached"));
        assertEquals("REDIS", repeated.get("cache_source"));
        assertEquals(1, ((List<?>) repeated.get("results")).size());
        verify(fixture.db, never()).getJobMatchingRunsById(any(), any());
        verify(fixture.db, never()).listJobMatchResultsByMatchingRunId(any());
        verify(fixture.db, never()).findJobMatchingRunsByUserId(any(), any());
        verify(fixture.ai, never()).generate(anyString(), anyMap());
        verify(fixture.ai, never()).embed(anyString());
        verify(fixture.resumes, times(2)).owned(fixture.cv);
        verify(fixture.db).readMarketJobPostsByStatus();
    }

    @Test
    void databaseFallbackWarmsFullResponseForNextRedisHit() {
        Fixture fixture = new Fixture();
        fixture.match();
        fixture.cached.clear();
        clearInvocations(fixture.db, fixture.values);

        Map<String, Object> database = fixture.match();

        assertEquals("DATABASE", database.get("cache_source"));
        verify(fixture.db).getJobMatchingRunsById(any(), eq(fixture.owner));
        verify(fixture.values)
            .set(startsWith("edunexus:matching:response:v2:"), contains("Java Developer"), any(Duration.class));
        clearInvocations(fixture.db);
        assertEquals("REDIS", fixture.match().get("cache_source"));
        verify(fixture.db, never()).getJobMatchingRunsById(any(), any());
        verify(fixture.db, never()).listJobMatchResultsByMatchingRunId(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "legacy-id", "wrong-owner", "wrong-basis"})
    void invalidResponsesFallBackToOwnedDatabaseRun(
        String invalid) {
        Fixture fixture = new Fixture();
        Map<String, Object> first = fixture.match();
        String key = fixture.cached.keySet().iterator().next();
        String original = fixture.cached.get(key);
        String corrupted = switch (invalid) {
            case "legacy-id" -> UUID.randomUUID().toString();
            case "wrong-owner" -> original.replace(fixture.owner.toString(), UUID.randomUUID().toString());
            case "wrong-basis" -> original.replace("SELECTED_CV", "CAREER_PROFILE");
            default -> invalid;
        };
        fixture.cached.put(key, corrupted);
        clearInvocations(fixture.db);

        Map<String, Object> result = fixture.match();

        assertEquals(first.get("id"), result.get("id"));
        assertEquals("DATABASE", result.get("cache_source"));
        verify(fixture.db).getJobMatchingRunsById(any(), eq(fixture.owner));
        verify(fixture.db, never()).insertJobMatchingRuns(anyMap());
    }

    @Test
    void redisOutageKeepsDatabaseCacheAvailable() {
        Fixture fixture = new Fixture();
        Map<String, Object> first = fixture.match();
        when(fixture.values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));

        Map<String, Object> result = fixture.match();

        assertEquals(first.get("id"), result.get("id"));
        assertEquals("DATABASE", result.get("cache_source"));
        verify(fixture.db, times(1)).insertJobMatchingRuns(anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CV", "CATALOG", "MODEL", "PROMPT"})
    void changedInputsCannotReuseCachedResponse(
        String change) {
        Fixture fixture = new Fixture();
        Map<String, Object> first = fixture.match();
        switch (change) {
            case "CV" -> fixture.canonical.set("Java and Spring Boot developer");
            case "CATALOG" -> fixture.catalogFingerprint.set("new-active-alias-or-displayed-metadata");
            case "MODEL" -> fixture.model.set("changed-model");
            case "PROMPT" -> fixture.prompt.set("changed-prompt-version");
            default -> fail("Unhandled change");
        }

        Map<String, Object> changed = fixture.match();

        assertEquals(false, changed.get("cached"));
        assertNotEquals(first.get("id"), changed.get("id"));
        verify(fixture.db, times(2)).insertJobMatchingRuns(anyMap());
    }

    @Test
    void lifetimeDoesNotExtendAnOldRunOrSurviveItsJobDeadline() {
        Fixture fixture = new Fixture();
        fixture.completedAge = Duration.ofMinutes(50);
        fixture.validThrough = OffsetDateTime.now().plusMinutes(2);

        fixture.match();

        ArgumentCaptor<Duration> lifetime = ArgumentCaptor.forClass(Duration.class);
        verify(fixture.values).set(anyString(), anyString(), lifetime.capture());
        assertTrue(lifetime.getValue().compareTo(Duration.ofMinutes(2)) <= 0);
        assertTrue(lifetime.getValue().compareTo(Duration.ofMinutes(1)) > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExpiredResponseIsNotReturnedEvenWhenRedisStillContainsIt() {
        Fixture fixture = new Fixture();
        fixture.validThrough = OffsetDateTime.now().plusMinutes(2);
        fixture.match();
        String key = fixture.cached.keySet().iterator().next();
        JsonMapper json = JsonMapper.builder().build();
        Map<String, Object> envelope = json.readValue(fixture.cached.get(key), Map.class);
        Map<?, ?> response = (Map<?, ?>) envelope.get("response");
        Map<String, Object> row = (Map<String, Object>) ((List<?>) response.get("results")).getFirst();
        row.put("valid_through", OffsetDateTime.now().minusMinutes(1).toString());
        fixture.cached.put(key, json.writeValueAsString(envelope));
        clearInvocations(fixture.db);

        assertEquals("DATABASE", fixture.match().get("cache_source"));

        verify(fixture.db).getJobMatchingRunsById(any(), eq(fixture.owner));
    }

    private static final class Fixture {

        final MatchingRepository db = mock(MatchingRepository.class);

        final CurrentUser user = mock(CurrentUser.class);

        final AiService ai = mock(AiService.class);

        final ResumeService resumes = mock(ResumeService.class);

        final ValueOperations<String, String> values = mock(ValueOperations.class);

        final UUID owner = UUID.randomUUID(), cv = UUID.randomUUID(), job = UUID.randomUUID();

        final AtomicReference<String> canonical = new AtomicReference<>("Java developer");

        final AtomicReference<String> catalogFingerprint = new AtomicReference<>("catalog-v1");

        final AtomicReference<String> model = new AtomicReference<>("test-chat");

        final AtomicReference<String> prompt = new AtomicReference<>("prompt-v1");

        final Map<String, String> cached = new HashMap<>();

        final Map<String, UUID> hashes = new HashMap<>();

        final Map<UUID, Map<String, Object>> runs = new HashMap<>();

        final Map<UUID, List<Map<String, Object>>> results = new HashMap<>();

        final MatchingService service;

        Duration completedAge = Duration.ofSeconds(1);

        OffsetDateTime validThrough;

        @SuppressWarnings("unchecked")
        Fixture() {
            when(user.id()).thenReturn(owner);
            when(ai.provider()).thenReturn("openai");
            when(ai.model()).thenAnswer(ignored -> model.get());
            when(ai.embeddingModel()).thenReturn("test-embedding");
            when(ai.cacheFingerprint("JOB_MATCH_RERANK")).thenAnswer(ignored -> prompt.get());
            when(resumes.owned(cv)).thenReturn(Map.of("id", cv, "user_id", owner, "status", "READY"));
            when(resumes.canonicalText(anyMap())).thenAnswer(ignored -> canonical.get());
            when(db.findCandidateProfilesByUserId(owner)).thenReturn(Optional.empty());
            when(db.listMarketJobSkillsByMarketJobId()).thenAnswer(
                ignored -> List.of(
                    new LinkedHashMap<>(
                        Map.of(
                            "id",
                            job,
                            "job_title",
                            "Java Developer",
                            "skills",
                            List.of(Map.of("name", "java", "required", true))
                        )
                    )
                )
            );
            when(db.readMarketJobPostsByStatus()).thenAnswer(ignored -> catalogFingerprint.get());
            when(db.json(any())).thenAnswer(call -> call.getArgument(0));
            when(db.findJobMatchingRunsByUserId(eq(owner), anyString())).thenAnswer(call -> {
                UUID id = hashes.get(call.getArgument(1, String.class));
                return id == null ? Optional.empty() : Optional.of(Map.of("id", id));
            });
            when(db.insertJobMatchingRuns(anyMap())).thenAnswer(call -> {
                Map<String, Object> run = new LinkedHashMap<>(call.getArgument(0));
                UUID id = UUID.randomUUID();
                run.put("id", id);
                runs.put(id, run);
                hashes.put(((Map<?, ?>) run.get("profile_snapshot")).get("request_hash").toString(), id);
                return id;
            });
            when(db.insertJobMatchResults(anyMap())).thenAnswer(call -> {
                Map<String, Object> result = new LinkedHashMap<>(call.getArgument(0));
                UUID id = UUID.randomUUID();
                UUID runId = (UUID) result.get("matching_run_id");
                result.put("id", id);
                result.put("job_status", "ACTIVE");
                result.put("job_title", "Java Developer");
                result.put("valid_through", validThrough);
                results.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(result);
                return id;
            });
            when(db.updateJobMatchingRunsById(any())).thenAnswer(call -> {
                Map<String, Object> run = runs.get(call.getArgument(0, UUID.class));
                run.put("status", "COMPLETED");
                run.put("completed_at", OffsetDateTime.now().minus(completedAge));
                return 1;
            });
            when(db.getJobMatchingRunsById(any(), eq(owner)))
                .thenAnswer(call -> new LinkedHashMap<>(runs.get(call.getArgument(0, UUID.class))));
            when(db.listJobMatchResultsByMatchingRunId(any()))
                .thenAnswer(call -> new ArrayList<>(results.getOrDefault(call.getArgument(0, UUID.class), List.of())));
            ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            when(provider.getIfAvailable()).thenReturn(template);
            when(template.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(call -> cached.get(call.getArgument(0, String.class)));
            doAnswer(call -> {
                cached.put(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(values).set(anyString(), anyString(), any(Duration.class));
            MatchingSalaryPolicyService policies = mock(MatchingSalaryPolicyService.class);
            when(policies.effective()).thenReturn(MatchingSalaryPolicy.legacyDefault());
            service = new MatchingService(db, user, ai, resumes, provider, policies);
        }

        Map<String, Object> match() {
            return service.match("SELECTED_CV", List.of(cv), 5);
        }
    }
}
