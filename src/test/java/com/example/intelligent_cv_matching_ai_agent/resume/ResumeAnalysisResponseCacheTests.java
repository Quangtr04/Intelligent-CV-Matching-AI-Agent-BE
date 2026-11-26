package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ResumeAnalysisResponseCacheTests {

    private final ResumeRepository db = mock(ResumeRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);

    private final StringRedisTemplate client = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final UUID actor = UUID.randomUUID(), resumeId = UUID.randomUUID();

    private final Map<String, String> cachedValues = new HashMap<>();

    private final AtomicReference<Map<String, Object>> persisted = new AtomicReference<>();

    private final Map<String, Object> resume = new LinkedHashMap<>();

    private final ResumeService service = new ResumeService(
        db,
        user,
        ai,
        mock(ResumeFileExtractor.class),
        mapper,
        "./target/resume-cache-test-storage",
        redis
    );

    @BeforeEach
    void configure() {
        resume.put("id", resumeId);
        resume.put("user_id", actor);
        resume.put("status", "READY");
        resume.put("title", "Java CV");
        resume.put("language", "vi");
        resume.put("source", "MANUAL");
        resume.put("raw_content", "");
        resume.put("structured_data", ResumeData.normalize(Map.of("summary", "Reviewed Java experience")));
        when(user.id()).thenReturn(actor);
        when(db.getResumesById(resumeId)).thenReturn(resume);
        when(ai.enabled()).thenReturn(true);
        when(ai.provider()).thenReturn("openai");
        when(ai.model()).thenReturn("gpt-test");
        when(ai.cacheFingerprint("CV_ANALYZE")).thenReturn("prompt-v1");
        when(ai.generateResumeAts(anyMap())).thenAnswer(call -> ResumeWritingTestData.report());
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(db.insertResumeAnalyses(anyMap())).thenAnswer(call -> {
            Map<String, Object> row = new LinkedHashMap<>(call.getArgument(0));
            row.put("id", UUID.randomUUID());
            row.put("created_at", OffsetDateTime.parse("2026-10-28T00:00:00Z"));
            persisted.set(row);
            return row.get("id");
        });
        when(db.getResumeAnalysesById(any(), eq(resumeId))).thenAnswer(call -> new LinkedHashMap<>(persisted.get()));
        when(db.listResumeAnalysisSuggestionsByAnalysisId(any())).thenReturn(
            List.of(Map.of("id", UUID.randomUUID(), "suggested_content", "Clarify the verified Java project"))
        );
        when(redis.getIfAvailable()).thenReturn(client);
        when(client.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cachedValues.get(call.getArgument(0)));
        doAnswer(call -> {
            cachedValues.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisHitReturnsTheCompleteAnalysisWithoutLoadingResultRowsOrCallingAi() {
        Map<String, Object> first = service.analyze(resumeId);
        assertEquals(false, first.get("cached"));
        assertEquals(1, cachedValues.size());
        assertTrue(cachedValues.values().iterator().next().contains("Clarify the verified Java project"));
        clearInvocations(db, ai);

        Map<String, Object> second = service.analyze(resumeId);

        assertEquals(first.get("id").toString(), second.get("id").toString());
        assertEquals(first.get("summary"), second.get("summary"));
        assertEquals(
            mapper.writeValueAsString(first.get("suggestions")),
            mapper.writeValueAsString(second.get("suggestions"))
        );
        assertEquals(true, second.get("cached"));
        verify(db).getResumesById(resumeId);
        verify(user, atLeastOnce()).owns(resume, "user_id");
        verify(db, never()).getResumeAnalysesById(any(), any());
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
        verify(db, never()).listResumeAnalysisSuggestionsByAnalysisId(any());
        verify(db, never()).insertResumeAnalyses(anyMap());
        verify(ai, never()).generateResumeAts(anyMap());
        verify(values).set(
            anyString(),
            anyString(),
            argThat(
                (
                    Duration ttl) -> !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(Duration.ofHours(24)) <= 0
            )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "raw_content", "language", "model", "provider", "prompt", "mode"})
    void changedAnalysisInputDoesNotReuseTheOldResponse(
        String change) {
        Map<String, Object> first = service.analyze(resumeId);
        String firstKey = cachedValues.keySet().iterator().next();
        switch (change) {
            case "content" -> resume.put(
                "structured_data",
                ResumeData.normalize(Map.of("summary", "Reviewed Java experience with Spring Boot"))
            );
            case "raw_content" -> resume.put("raw_content", "Original extraction changed");
            case "language" -> resume.put("language", "en");
            case "model" -> when(ai.model()).thenReturn("gpt-new");
            case "provider" -> when(ai.provider()).thenReturn("another-api-provider");
            case "prompt" -> when(ai.cacheFingerprint("CV_ANALYZE")).thenReturn("prompt-v2");
            case "mode" -> when(ai.enabled()).thenReturn(false);
            default -> fail("Unknown test case");
        }

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, cachedValues.size());
        assertTrue(cachedValues.containsKey(firstKey));
        assertTrue(cachedValues.keySet().stream().anyMatch(key -> !firstKey.equals(key)));
        verify(db, times(2)).insertResumeAnalyses(anyMap());
        verify(ai, times("mode".equals(change) ? 1 : 2)).generateResumeAts(anyMap());
    }

    @Test
    void ownerAndReadyChecksStillRunBeforeReadingRedis() {
        service.analyze(resumeId);
        clearInvocations(values, ai, db);
        resume.put("status", "REVIEW_REQUIRED");

        assertEquals(409, assertThrows(ApiException.class, () -> service.analyze(resumeId)).status());

        verify(values, never()).get(anyString());
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
        verify(ai, never()).generateResumeAts(anyMap());
        resume.put("status", "READY");
        doThrow(ApiException.forbidden("CV belongs to another user")).when(user).owns(resume, "user_id");
        assertThrows(ApiException.class, () -> service.analyze(resumeId));
        verify(values, never()).get(anyString());
        verify(db, never()).getResumeAnalysesById(any(), any());
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
    }

    @Test
    void redisConnectionFailureFallsBackToNormalGenerationAndPersistence() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("Offline"));
        doThrow(new RedisConnectionFailureException("Offline")).when(values)
            .set(anyString(), anyString(), any(Duration.class));

        Map<String, Object> response = service.analyze(resumeId);

        assertEquals(false, response.get("cached"));
        assertNotNull(response.get("id"));
        verify(ai).generateResumeAts(anyMap());
        verify(db).insertResumeAnalyses(anyMap());
    }

    @Test
    void malformedRedisValueRegeneratesTheAnalysis() {
        Map<String, Object> first = service.analyze(resumeId);
        cachedValues.replaceAll(
            (
                key,
                value) -> "{broken-json"
        );

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        verify(ai, times(2)).generateResumeAts(anyMap());
        verify(db, times(2)).insertResumeAnalyses(anyMap());
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "disabled", "offline"})
    void redisExpiryOrUnavailabilityRegeneratesTheAnalysis(
        String redisState) {
        Map<String, Object> first = service.analyze(resumeId);
        String key = cachedValues.keySet().iterator().next();
        assertTrue(key.substring(key.lastIndexOf(':') + 1).matches("[0-9a-f]{64}"));
        clearInvocations(db, ai, values);
        cachedValues.clear();
        switch (redisState) {
            case "expired" -> {
            }
            case "disabled" -> when(redis.getIfAvailable()).thenReturn(null);
            case "offline" -> {
                when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("Offline"));
                doThrow(new RedisConnectionFailureException("Offline")).when(values)
                    .set(anyString(), anyString(), any(Duration.class));
            }
            default -> fail("Unknown Redis state");
        }

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("suggestions"), second.get("suggestions"));
        assertEquals(first.get("writing_quality"), second.get("writing_quality"));
        assertEquals(false, second.get("cached"));
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
        verify(db).getResumeAnalysesById(second.get("id"), resumeId);
        verify(db).listResumeAnalysisSuggestionsByAnalysisId(second.get("id"));
        verify(db).insertResumeAnalyses(anyMap());
        verify(ai).generateResumeAts(anyMap());
        if ("expired".equals(redisState)) {
            assertEquals(1, cachedValues.size());
            verify(values).set(
                anyString(),
                anyString(),
                argThat(
                    (
                        Duration ttl) -> ttl.compareTo(Duration.ofHours(24)) <= 0
                            && ttl.compareTo(Duration.ofHours(23)) > 0
                )
            );
        }
    }

    @Test
    void redisIdentityWithoutAnInputHashIsNotReused() {
        Map<String, Object> first = service.analyze(resumeId);
        String key = cachedValues.keySet().iterator().next();
        Map<String, Object> envelope = mapper.readValue(cachedValues.get(key), Map.class);
        Map<String, Object> identity = new LinkedHashMap<>((Map<String, Object>) envelope.get("identity"));
        identity.remove("input_hash");
        envelope.put("identity", identity);
        cachedValues.put(key, mapper.writeValueAsString(envelope));

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        verify(ai, times(2)).generateResumeAts(anyMap());
    }

    @Test
    void malformedRedisAnalysisCannotBeReturnedAsACacheHit() {
        Map<String, Object> first = service.analyze(resumeId);
        String key = cachedValues.keySet().iterator().next();
        Map<String, Object> envelope = mapper.readValue(cachedValues.get(key), Map.class);
        Map<String, Object> response = new LinkedHashMap<>((Map<String, Object>) envelope.get("response"));
        response.put("id", "invalid");
        envelope.put("response", response);
        cachedValues.put(key, mapper.writeValueAsString(envelope));

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        verify(ai, times(2)).generateResumeAts(anyMap());
    }

    @Test
    void cacheForAnotherActorCannotBeReused() {
        Map<String, Object> first = service.analyze(resumeId);
        when(user.id()).thenReturn(UUID.randomUUID());

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, cachedValues.size());
        verify(ai, times(2)).generateResumeAts(anyMap());
    }
}
