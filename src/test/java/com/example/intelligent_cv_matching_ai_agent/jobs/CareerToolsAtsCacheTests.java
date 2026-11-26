package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.CareerToolsService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class CareerToolsAtsCacheTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final AiService ai = mock(AiService.class);

    private final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);

    private final StringRedisTemplate client = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final CareerToolsService service =
        new CareerToolsService(new CareerToolsRepository(db), user, mock(MatchingService.class), resumes, ai, redis);

    private final JsonMapper json = JsonMapper.builder().build();

    private final UUID actor = UUID.randomUUID(), firstCv = UUID.randomUUID(), secondCv = UUID.randomUUID(),
        job = UUID.randomUUID();

    private final Map<String, Object> cv = new LinkedHashMap<>();

    private final Map<String, Object> target = new LinkedHashMap<>();

    private final Map<String, String> memory = new LinkedHashMap<>();

    private final Map<UUID, Map<String, Object>> saved = new LinkedHashMap<>();

    @BeforeEach
    void configure() {
        when(user.id()).thenReturn(actor);
        cv.putAll(
            Map.of(
                "id",
                firstCv,
                "status",
                "READY",
                "language",
                "vi",
                "structured_data",
                Map.of("summary", "Java developer")
            )
        );
        target.putAll(Map.of("id", job, "raw_content_hash", "job-content-hash", "job_title", "Java developer"));
        when(resumes.owned(firstCv)).thenAnswer(call -> new LinkedHashMap<>(cv));
        when(resumes.owned(secondCv)).thenAnswer(call -> {
            Map<String, Object> another = new LinkedHashMap<>(cv);
            another.put("id", secondCv);
            return another;
        });
        when(resumes.canonicalText(anyMap())).thenReturn("Reviewed Java developer CV");
        when(db.one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job)))
            .thenAnswer(call -> new LinkedHashMap<>(target));
        when(ai.provider()).thenReturn("openai");
        when(ai.model()).thenReturn("test-model");
        when(ai.cacheFingerprint("JOB_ATS")).thenReturn("prompt-v1");
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(generated());
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(db.insert(eq("job_resume_analyses"), anyMap())).thenAnswer(call -> {
            UUID id = UUID.randomUUID();
            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) call.getArgument(1));
            row.put("id", id);
            row.put("created_at", OffsetDateTime.parse("2026-10-28T08:00:00+07:00"));
            saved.put(id, row);
            return id;
        });
        when(db.one(eq("SELECT * FROM job_resume_analyses WHERE id=?"), any(UUID.class)))
            .thenAnswer(call -> new LinkedHashMap<>(saved.get(call.getArgument(1))));
        when(redis.getIfAvailable()).thenReturn(client);
        when(client.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> memory.get(call.getArgument(0)));
        doAnswer(call -> {
            memory.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void sendsTheFullJobDescriptionAndConfirmedCvInOneAtsRequest() {
        String description = "Responsibilities: " + "Java services ".repeat(3000) + "\nRequirements: PostgreSQL.";
        target.put("original_content", description);
        target.put("raw_content", "Short summary");
        var scored = new LinkedHashMap<>(generated());
        scored.put("dimension_scores", fields("skills", 82, "experience", 67, "education", null));
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(scored);
        var response = service.ats(job, new CvRequest(firstCv));
        assertEquals(scored.get("dimension_scores"), response.get("dimension_scores"));
        assertEquals(
            response.get("dimension_scores"),
            service.ats(job, new CvRequest(firstCv)).get("dimension_scores")
        );

        verify(ai).generate(
            eq("JOB_ATS"),
            argThat(
                input -> description.equals(input.get("jd_text"))
                    && "Reviewed Java developer CV".equals(input.get("resume_text"))
            )
        );
    }

    @Test
    void fullResponseHitDoesNotReadAnalysisRowsOrCallAiAgain() {
        Map<String, Object> first = service.ats(job, new CvRequest(firstCv));
        assertEquals(false, first.get("cached"));
        String key = onlyKey();
        assertTrue(key.startsWith("edunexus:ats:response:v1:" + actor + ":" + firstCv + ":" + job + ":"));
        Map<String, Object> envelope = json.readValue(memory.get(key), Map.class);
        assertEquals(first.get("id").toString(), ((Map<?, ?>) envelope.get("response")).get("id"));
        assertEquals(List.of("Java"), ((Map<?, ?>) envelope.get("response")).get("strengths_json"));
        verify(values).set(
            eq(key),
            anyString(),
            argThat(
                (
                    Duration ttl) -> !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofHours(24)) <= 0
            )
        );
        clearInvocations(db, ai, values, resumes, user);

        Map<String, Object> cached = service.ats(job, new CvRequest(firstCv));

        assertEquals(true, cached.get("cached"));
        Map<String, Object> expected = new LinkedHashMap<>(first);
        expected.put("cached", true);
        assertEquals(json.readTree(json.writeValueAsString(expected)), json.valueToTree(cached));
        assertNoAnalysisReads();
        verify(db, never()).insert(eq("job_resume_analyses"), anyMap());
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
        verify(resumes).owned(firstCv);
        verify(user).requireRole("CANDIDATE");
        verify(db).one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-cv", "foreign-cv", "archived-cv", "inactive-job", "expired-job"})
    void unavailableSelectionsAreExplainedBeforeAiOrQuotaCanBeUsed(
        String resource) {
        if (resource.endsWith("job")) {
            doThrow(ApiException.notFound("Resource not found")).when(db)
                .one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job));
        } else if ("archived-cv".equals(resource)) {
            cv.put("status", "ARCHIVED");
        } else {
            doThrow(ApiException.notFound("Resource not found")).when(resumes).owned(firstCv);
        }

        ApiException failure = assertThrows(ApiException.class, () -> service.ats(job, new CvRequest(firstCv)));

        assertEquals(404, failure.status());
        assertEquals(
            resource.endsWith("job")
                ? "This job is no longer available. Reload the job list and select an active posting."
                : "This CV is unavailable. Reload your CV list and select a confirmed CV.",
            failure.getMessage()
        );
        verifyNoInteractions(ai);
        verify(redis, never()).getIfAvailable();
        verify(db, never()).insert(eq("job_resume_analyses"), anyMap());
    }

    @Test
    void identicalCvContentsWithDifferentIdsUseSeparateFullResponseKeys() {
        Map<String, Object> first = service.ats(job, new CvRequest(firstCv));
        Map<String, Object> second = service.ats(job, new CvRequest(secondCv));
        assertEquals(firstCv, first.get("resume_id"));
        assertEquals(secondCv, second.get("resume_id"));
        assertEquals(2, memory.size());
        assertNotEquals(first.get("id"), second.get("id"));
        clearInvocations(ai, db);
        assertEquals(firstCv.toString(), service.ats(job, new CvRequest(firstCv)).get("resume_id"));
        assertEquals(secondCv.toString(), service.ats(job, new CvRequest(secondCv)).get("resume_id"));
        assertNoAnalysisReads();
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "invalid-json",
            "legacy-id",
            "wrong-version",
            "wrong-owner",
            "wrong-cv",
            "wrong-job",
            "wrong-hash",
            "missing-score",
            "invalid-score",
            "missing-suggestions"}
    )
    void corruptOrMismatchedResponsesAreMisses(
        String kind) {
        Map<String, Object> first = service.ats(job, new CvRequest(firstCv));
        String key = onlyKey();
        Map<String, Object> envelope = json.readValue(memory.get(key), Map.class);
        Map<String, Object> response = (Map<String, Object>) envelope.get("response");
        switch (kind) {
            case "wrong-version" -> envelope.put("schema_version", 99);
            case "wrong-owner" -> response.put("user_id", UUID.randomUUID().toString());
            case "wrong-cv" -> response.put("resume_id", secondCv.toString());
            case "wrong-job" -> response.put("market_job_id", UUID.randomUUID().toString());
            case "wrong-hash" -> ((Map<String, Object>) envelope.get("identity")).put("input_hash", "stale-hash");
            case "missing-score" -> response.remove("ats_score");
            case "invalid-score" -> response.put("ats_score", 101);
            case "missing-suggestions" -> response.remove("improvement_suggestions_json");
            default -> {
            }
        }
        memory.put(key, switch (kind) {
            case "invalid-json" -> "not-json";
            case "legacy-id" -> first.get("id").toString();
            default -> json.writeValueAsString(envelope);
        });
        clearInvocations(db, ai);

        Map<String, Object> fresh = service.ats(job, new CvRequest(firstCv));

        assertEquals(false, fresh.get("cached"));
        assertNotEquals(first.get("id").toString(), fresh.get("id").toString());
        assertEquals(firstCv, fresh.get("resume_id"));
        assertEquals(job, fresh.get("market_job_id"));
        verify(ai).generate(eq("JOB_ATS"), anyMap());
    }

    @Test
    void legacyIdNamespaceIsNotReadAsAResponseCache() {
        Map<String, Object> first = service.ats(job, new CvRequest(firstCv));
        String responseKey = onlyKey();
        memory.clear();
        String legacyKey = responseKey.replace("edunexus:ats:response:v1:", "edunexus:ats:");
        memory.put(legacyKey, first.get("id").toString());
        clearInvocations(values, ai);

        Map<String, Object> fresh = service.ats(job, new CvRequest(firstCv));

        assertEquals(false, fresh.get("cached"));
        verify(values).get(responseKey);
        verify(values, never()).get(legacyKey);
        verify(ai).generate(eq("JOB_ATS"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cv-content", "cv-text", "language", "job-content", "job-metadata", "model", "prompt"})
    void changesToEffectiveInputsUseAnotherCacheEntry(
        String change) {
        Map<String, Object> first = service.ats(job, new CvRequest(firstCv));
        String oldKey = onlyKey();
        switch (change) {
            case "cv-content" -> cv.put("structured_data", Map.of("summary", "Java and Spring Boot developer"));
            case "cv-text" -> when(resumes.canonicalText(anyMap())).thenReturn("New reviewed CV text");
            case "language" -> cv.put("language", "en");
            case "job-content" -> target.put("raw_content_hash", "new-job-content-hash");
            case "job-metadata" -> target.put("job_title", "Senior Java developer");
            case "model" -> when(ai.model()).thenReturn("new-model");
            case "prompt" -> when(ai.cacheFingerprint("JOB_ATS")).thenReturn("prompt-v2");
            default -> fail("Unhandled test input");
        }
        clearInvocations(ai, values);

        Map<String, Object> fresh = service.ats(job, new CvRequest(firstCv));

        assertEquals(false, fresh.get("cached"));
        assertNotEquals(first.get("id"), fresh.get("id"));
        assertEquals(2, memory.size());
        ArgumentCaptor<String> requestedKey = ArgumentCaptor.forClass(String.class);
        verify(values).get(requestedKey.capture());
        assertNotEquals(oldKey, requestedKey.getValue());
        verify(ai).generate(eq("JOB_ATS"), anyMap());
    }

    @Test
    void aDatabaseHitRewarmsRedisWithTheFullResponse() {
        UUID existing = (UUID) service.ats(job, new CvRequest(firstCv)).get("id");
        memory.clear();
        stubDatabaseHit(existing);
        clearInvocations(ai, db, values);

        Map<String, Object> databaseHit = service.ats(job, new CvRequest(firstCv));

        assertEquals(existing, databaseHit.get("id"));
        assertEquals(true, databaseHit.get("cached"));
        assertEquals(1, memory.size());
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
        clearInvocations(ai, db);
        assertEquals(existing.toString(), service.ats(job, new CvRequest(firstCv)).get("id"));
        assertNoAnalysisReads();
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
    }

    @Test
    void redisReadAndWriteFailuresStillAllowTheDatabaseCacheToReturn() {
        UUID existing = (UUID) service.ats(job, new CvRequest(firstCv)).get("id");
        stubDatabaseHit(existing);
        doThrow(new RedisConnectionFailureException("unavailable")).when(values).get(anyString());
        doThrow(new QueryTimeoutException("cache command timed out")).when(values)
            .set(anyString(), anyString(), any(Duration.class));
        clearInvocations(ai, db);

        Map<String, Object> result = assertDoesNotThrow(() -> service.ats(job, new CvRequest(firstCv)));

        assertEquals(existing, result.get("id"));
        assertEquals(true, result.get("cached"));
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
        verify(db, never()).insert(eq("job_resume_analyses"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "owner", "review-required", "inactive-job"})
    void authorizationAndCurrentResourceChecksRunBeforeAWarmCache(
        String invalid) {
        service.ats(job, new CvRequest(firstCv));
        switch (invalid) {
            case "role" -> doThrow(ApiException.forbidden("Candidate required")).when(user).requireRole("CANDIDATE");
            case "owner" -> doThrow(ApiException.notFound("Resource not found")).when(resumes).owned(firstCv);
            case "review-required" -> cv.put("status", "REVIEW_REQUIRED");
            case "inactive-job" -> doThrow(ApiException.notFound("Resource not found")).when(db)
                .one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job));
            default -> fail("Unhandled guard");
        }
        clearInvocations(values, ai);

        assertThrows(ApiException.class, () -> service.ats(job, new CvRequest(firstCv)));

        verifyNoInteractions(values);
        verify(ai, never()).generate(eq("JOB_ATS"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider-error", "invalid-score", "missing-score"})
    void aiErrorsAndInvalidScoresDoNotPersistOrCacheAnAnalysis(
        String failure) {
        if ("provider-error".equals(failure)) {
            when(ai.generate(eq("JOB_ATS"), anyMap())).thenThrow(new ApiException(503, "AI unavailable"));
        } else {
            Map<String, Object> invalid = new LinkedHashMap<>(generated());
            if ("invalid-score".equals(failure))
                invalid.put("ats_score", 101);
            else
                invalid.remove("ats_score");
            when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(invalid);
        }

        assertThrows(ApiException.class, () -> service.ats(job, new CvRequest(firstCv)));

        verify(db, never()).insert(eq("job_resume_analyses"), anyMap());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        assertTrue(memory.isEmpty());
    }

    @Test
    void externalApplyUsesTheActiveAliasAndPersistsThatExactTarget() {
        String active = "https://www.topcv.vn/viec-lam/active-java-developer/1234567.html";
        when(db.one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job))).thenReturn(
            Map.of("id", job, "source_url", active, "original_source_url", "https://itviec.com/it-jobs/expired-primary")
        );
        UUID event = UUID.randomUUID();
        when(db.insert(eq("external_apply_events"), anyMap())).thenReturn(event);

        Map<String, Object> result = service.apply(job, null);

        assertEquals(active, result.get("target_url"));
        assertEquals(event, result.get("event_id"));
        verify(db).insert(
            eq("external_apply_events"),
            argThat(
                fields -> active.equals(fields.get("target_url")) && actor.equals(fields.get("user_id"))
                    && job.equals(fields.get("market_job_id"))
            )
        );
    }

    @Test
    void externalApplyDoesNotReviveAnExpiredKnownSource() {
        when(db.one(contains("FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE'"), eq(job)))
            .thenReturn(Map.of("id", job, "original_source_url", "https://itviec.com/it-jobs/expired-primary"));
        ApiException error = assertThrows(ApiException.class, () -> service.apply(job, null));
        assertEquals(409, error.status());
        verify(db, never()).insert(eq("external_apply_events"), anyMap());
    }

    private Map<String, Object> generated() {
        return Map.of(
            "ats_score",
            80,
            "strengths",
            List.of("Java"),
            "missing_skills",
            List.of("Cloud deployment"),
            "improvement_suggestions",
            List.of("Describe the project")
        );
    }

    private String onlyKey() {
        assertEquals(1, memory.size());
        return memory.keySet().iterator().next();
    }

    private void stubDatabaseHit(
        UUID id) {
        when(db.optional(contains("FROM job_resume_analyses WHERE user_id=?"), any(), any(), any(), any()))
            .thenAnswer(call -> Optional.of(new LinkedHashMap<>(saved.get(id))));
    }

    private void assertNoAnalysisReads() {
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call -> {
            String method = call.getMethod().getName();
            Object[] args = call.getArguments();
            return Set.of("one", "optional", "list").contains(method) && args.length > 0
                && args[0] instanceof String sql && sql.contains("job_resume_analyses");
        }), "A valid Redis response must not query PostgreSQL analysis rows");
    }
}
