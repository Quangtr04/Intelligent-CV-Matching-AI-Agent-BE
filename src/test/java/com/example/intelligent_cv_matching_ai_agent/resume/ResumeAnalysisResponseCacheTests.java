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
import java.util.Optional;
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
        when(ai.provider()).thenReturn("ollama");
        when(ai.model()).thenReturn("qwen-test");
        when(ai.cacheFingerprint("CV_ANALYZE")).thenReturn("prompt-v1");
        when(ai.generate(eq("CV_ANALYZE"), anyMap())).thenAnswer(call -> ResumeWritingTestData.report());
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(db.insertResumeAnalyses(anyMap())).thenAnswer(call -> {
            Map<String, Object> row = new LinkedHashMap<>(call.getArgument(0));
            row.put("id", UUID.randomUUID());
            row.put("created_at", OffsetDateTime.parse("2026-10-28T00:00:00Z"));
            persisted.set(row);
            return row.get("id");
        });
        when(db.findResumeAnalysisByInput(eq(resumeId), anyString(), anyString(), any(UUID.class))).thenAnswer(call -> {
            Map<String, Object> row = persisted.get();
            boolean matches = row != null && call.getArgument(1).equals(row.get("analysis_type"))
                && call.getArgument(2).equals(row.get("input_hash")) && actor.equals(call.getArgument(3))
                && "READY".equals(resume.get("status"));
            return matches ? Optional.of(Map.of("id", row.get("id"))) : Optional.empty();
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
        verify(ai, never()).generate(anyString(), anyMap());
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
        String firstHash = persisted.get().get("input_hash").toString();
        switch (change) {
            case "content" -> resume.put(
                "structured_data",
                ResumeData.normalize(Map.of("summary", "Reviewed Java experience with Spring Boot"))
            );
            case "raw_content" -> resume.put("raw_content", "Original extraction changed");
            case "language" -> resume.put("language", "en");
            case "model" -> when(ai.model()).thenReturn("qwen-new");
            case "provider" -> when(ai.provider()).thenReturn("another-self-hosted-provider");
            case "prompt" -> when(ai.cacheFingerprint("CV_ANALYZE")).thenReturn("prompt-v2");
            case "mode" -> when(ai.enabled()).thenReturn(false);
            default -> fail("Unknown test case");
        }

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertNotEquals(firstHash, persisted.get().get("input_hash"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, cachedValues.size());
        verify(db, times(2)).insertResumeAnalyses(anyMap());
        verify(ai, times("mode".equals(change) ? 1 : 2)).generate(eq("CV_ANALYZE"), anyMap());
    }

    @Test
    void ownerAndReadyChecksStillRunBeforeReadingRedis() {
        service.analyze(resumeId);
        clearInvocations(values, ai, db);
        resume.put("status", "REVIEW_REQUIRED");

        assertEquals(409, assertThrows(ApiException.class, () -> service.analyze(resumeId)).status());

        verify(values, never()).get(anyString());
        verify(db, never()).findResumeAnalysisByInput(any(), any(), any(), any());
        verify(ai, never()).generate(anyString(), anyMap());
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
        verify(ai).generate(eq("CV_ANALYZE"), anyMap());
        verify(db).insertResumeAnalyses(anyMap());
    }

    @Test
    void malformedRedisValueFallsBackToTheStoredAnalysis() {
        Map<String, Object> first = service.analyze(resumeId);
        cachedValues.replaceAll(
            (
                key,
                value) -> "{broken-json"
        );

        Map<String, Object> second = service.analyze(resumeId);

        assertEquals(first.get("id"), second.get("id"));
        assertEquals(true, second.get("cached"));
        verify(ai).generate(eq("CV_ANALYZE"), anyMap());
        verify(db).insertResumeAnalyses(anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "disabled", "offline"})
    void durableAnalysisSurvivesRedisExpiryOrUnavailabilityWithoutCallingAiAgain(
        String redisState) {
        Map<String, Object> first = service.analyze(resumeId);
        assertTrue(persisted.get().get("input_hash").toString().matches("[0-9a-f]{64}"));
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

        assertEquals(first.get("id"), second.get("id"));
        assertEquals(first.get("suggestions"), second.get("suggestions"));
        assertEquals(first.get("writing_quality"), second.get("writing_quality"));
        assertEquals(true, second.get("cached"));
        verify(db).findResumeAnalysisByInput(resumeId, "AI_QUALITY", persisted.get().get("input_hash"), actor);
        verify(db).getResumeAnalysesById(first.get("id"), resumeId);
        verify(db).listResumeAnalysisSuggestionsByAnalysisId(first.get("id"));
        verify(db, never()).insertResumeAnalyses(anyMap());
        verify(ai, never()).generate(anyString(), anyMap());
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
    void historicalAnalysisWithoutAnInputHashIsNotReused() {
        Map<String, Object> first = service.analyze(resumeId);
        persisted.get().remove("input_hash");
        cachedValues.clear();

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        verify(ai, times(2)).generate(eq("CV_ANALYZE"), anyMap());
    }

    @Test
    void malformedDurableAnalysisCannotBeReturnedAsACacheHit() {
        Map<String, Object> first = service.analyze(resumeId);
        persisted.get().put("overall_score", "invalid");
        cachedValues.clear();

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        verify(ai, times(2)).generate(eq("CV_ANALYZE"), anyMap());
    }

    @Test
    void cacheForAnotherActorCannotBeReused() {
        Map<String, Object> first = service.analyze(resumeId);
        when(user.id()).thenReturn(UUID.randomUUID());

        Map<String, Object> second = service.analyze(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, cachedValues.size());
        verify(ai, times(2)).generate(eq("CV_ANALYZE"), anyMap());
    }
}
