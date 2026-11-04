package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeProfileAlignmentRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeProfileAlignmentRefreshRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeProfileAlignmentService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ResumeProfileAlignmentCacheTests {

    private static final String FEATURE = "CV_PROFILE_ALIGNMENT";

    private final ResumeProfileAlignmentRepository db = mock(ResumeProfileAlignmentRepository.class);

    private final ResumeProfileAlignmentRefreshRepository refreshes =
        mock(ResumeProfileAlignmentRefreshRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);

    private final StringRedisTemplate client = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final UUID owner = UUID.randomUUID(), resumeId = UUID.randomUUID();

    private final Map<String, Object> resume = new LinkedHashMap<>();

    private final Map<String, Object> profile = new LinkedHashMap<>();

    private final Map<UUID, Map<String, Object>> history = new LinkedHashMap<>();

    private final Map<String, String> cache = new HashMap<>();

    private final ResumeProfileAlignmentService service =
        new ResumeProfileAlignmentService(db, user, ai, mapper, refreshes, redis);

    @BeforeEach
    void configure() {
        resume.putAll(
            Map.of(
                "id",
                resumeId,
                "user_id",
                owner,
                "status",
                "READY",
                "title",
                "Reviewed CV",
                "language",
                "en",
                "content_hash",
                FlowSupport.hash("original CV"),
                "structured_data",
                Map.of("summary", "Java Developer with Java experience")
            )
        );
        profile.put("target_job_title", "Java Developer");
        when(user.id()).thenReturn(owner);
        when(refreshes.isActiveCandidate(any(UUID.class))).thenReturn(true);
        when(db.resume(eq(resumeId), any(UUID.class))).thenAnswer(call -> {
            if (!resume.get("user_id").equals(call.getArgument(1))) {
                throw ApiException.notFound("CV not found");
            }
            return resume;
        });
        when(db.profile(any(UUID.class))).thenReturn(Optional.of(profile));
        when(db.lockProfile(any(UUID.class))).thenReturn(Optional.of(profile));
        when(db.lockResume(eq(resumeId), any(UUID.class))).thenReturn(Optional.of(resume));
        when(db.profileSkills(any(UUID.class))).thenReturn(List.of());
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(db.insert(anyMap())).thenAnswer(call -> {
            Map<String, Object> saved = new LinkedHashMap<>(call.getArgument(0));
            UUID id = UUID.randomUUID();
            saved.put("id", id);
            history.put(id, saved);
            return id;
        });
        when(db.get(any(UUID.class), eq(resumeId), any(UUID.class)))
            .thenAnswer(call -> new LinkedHashMap<>(history.get(call.getArgument(0))));
        when(db.reusable(eq(resumeId), any(UUID.class), anyString(), anyString(), anyString())).thenAnswer(
            call -> history.values().stream().filter(row -> row.get("user_id").equals(call.getArgument(1)))
                .filter(row -> row.get("resume_content_hash").equals(call.getArgument(2)))
                .filter(row -> row.get("profile_hash").equals(call.getArgument(3)))
                .filter(
                    row -> ((Map<?, ?>) row.get("result_json")).get("_cache_input_hash").equals(call.getArgument(4))
                ).findFirst()
        );
        when(ai.enabled()).thenReturn(true);
        when(ai.provider()).thenReturn("openai");
        when(ai.model()).thenReturn("comparison-model");
        when(ai.cacheFingerprint(FEATURE)).thenReturn("prompt-v1");
        when(ai.generate(eq(FEATURE), anyMap())).thenAnswer(call -> generated(call.getArgument(1)));
        when(ai.generateForUser(any(UUID.class), eq(FEATURE), anyMap()))
            .thenAnswer(call -> generated(call.getArgument(2)));
        when(redis.getIfAvailable()).thenReturn(client);
        when(client.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
        doAnswer(call -> {
            cache.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void repeatedUnchangedComparisonUsesRedisWithoutAiOrAnotherHistoryInsert() {
        Map<String, Object> first = service.create(resumeId);
        clearInvocations(db, ai);

        Map<String, Object> second = service.create(resumeId);

        assertEquals(first.get("id").toString(), second.get("id").toString());
        assertEquals(
            mapper.readValue(mapper.writeValueAsString(first.get("result_json")), Map.class),
            second.get("result_json")
        );
        assertEquals(true, second.get("cached"));
        assertEquals("REDIS", second.get("cache_source"));
        assertFalse(((Map<?, ?>) second.get("result_json")).containsKey("_cache_input_hash"));
        assertEquals(1, history.size());
        verify(db, never()).reusable(any(), any(), anyString(), anyString(), anyString());
        verify(db, never()).insert(anyMap());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "offline", "malformed", "wrong-owner", "tampered-score"})
    void redisProblemsReuseValidatedDurableResultWithoutAnotherModelCall(
        String problem) {
        Map<String, Object> first = service.create(resumeId);
        if (problem.equals("missing")) {
            cache.clear();
        } else if (problem.equals("offline")) {
            when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("Unavailable"));
            doThrow(new RedisConnectionFailureException("Unavailable")).when(values)
                .set(anyString(), anyString(), any(Duration.class));
        } else if (problem.equals("malformed")) {
            cache.replaceAll(
                (
                    key,
                    payload) -> "{broken-json"
            );
        } else {
            cache.replaceAll(
                (
                    key,
                    payload) -> {
                    Map<String, Object> envelope = mapper.readValue(payload, Map.class);
                    Map<String, Object> response = (Map<String, Object>) envelope.get("response");
                    if (problem.equals("wrong-owner")) {
                        response.put("user_id", UUID.randomUUID().toString());
                    } else {
                        ((Map<String, Object>) response.get("result_json")).put("score", 1);
                    }
                    return mapper.writeValueAsString(envelope);
                }
            );
        }
        when(ai.enabled()).thenReturn(false);
        clearInvocations(ai);

        Map<String, Object> second = service.create(resumeId);

        assertEquals(first.get("id"), second.get("id"));
        assertEquals(true, second.get("cached"));
        assertEquals("DATABASE", second.get("cache_source"));
        assertEquals(1, history.size());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cv-hash", "cv-evidence", "profile", "skills", "language", "model", "provider", "prompt"})
    void changedEffectiveInputsCannotReuseThePriorComparison(
        String change) {
        Map<String, Object> first = service.create(resumeId);
        switch (change) {
            case "cv-hash" -> resume.put("content_hash", FlowSupport.hash("new CV"));
            case "cv-evidence" -> resume.put("structured_data", Map.of("summary", "Reviewed Java Developer work"));
            case "profile" -> profile.put("target_job_title", "Backend Developer");
            case "skills" -> when(db.profileSkills(owner)).thenReturn(List.of(Map.of("name", "Java")));
            case "language" -> resume.put("language", "vi");
            case "model" -> when(ai.model()).thenReturn("comparison-model-v2");
            case "provider" -> when(ai.provider()).thenReturn("another-provider");
            case "prompt" -> when(ai.cacheFingerprint(FEATURE)).thenReturn("prompt-v2");
            default -> fail("Unknown input");
        }

        Map<String, Object> second = service.create(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, history.size());
        verify(ai, times(2)).generate(eq(FEATURE), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "owner", "status", "queued-refresh"})
    void authorizationAndReadyChecksPrecedeRedisEvenWhenAResponseIsCached(
        String invalid) {
        service.create(resumeId);
        clearInvocations(values, ai);
        switch (invalid) {
            case "account" -> when(refreshes.isActiveCandidate(owner)).thenReturn(false);
            case "owner" -> when(user.id()).thenReturn(UUID.randomUUID());
            case "status" -> resume.put("status", "REVIEW_REQUIRED");
            case "queued-refresh" -> when(refreshes.active(resumeId, owner)).thenReturn(true);
            default -> fail("Unknown authorization case");
        }

        assertThrows(ApiException.class, () -> service.create(resumeId));

        verify(values, never()).get(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void identicalContentFromAnotherOwnerHasAnIndependentCacheAndHistory() {
        Map<String, Object> first = service.create(resumeId);
        UUID anotherOwner = UUID.randomUUID();
        resume.put("user_id", anotherOwner);
        when(user.id()).thenReturn(anotherOwner);

        Map<String, Object> second = service.create(resumeId);

        assertNotEquals(first.get("id"), second.get("id"));
        assertEquals(false, second.get("cached"));
        assertEquals(2, cache.size());
        verify(ai, times(2)).generate(eq(FEATURE), anyMap());
    }

    @Test
    void profileChangedDuringACacheReadCannotReturnAnEarlierComparison() {
        service.create(resumeId);
        clearInvocations(db, ai);
        when(db.lockProfile(owner)).thenAnswer(call -> {
            profile.put("target_job_title", "Changed after cache lookup");
            return Optional.of(profile);
        });

        assertEquals(409, assertThrows(ApiException.class, () -> service.create(resumeId)).status());

        verify(db, never()).insert(anyMap());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void cachedBackgroundRefreshStillValidatesAndCompletesItsClaim() {
        service.create(resumeId);
        Map<?, ?> saved = history.values().iterator().next();
        UUID token = UUID.randomUUID();
        long generation = 3;
        when(refreshes.claim(resumeId, owner, token, false)).thenReturn(
            Optional.of(Map.of("generation", generation, "requested_profile_hash", saved.get("profile_hash")))
        );
        when(refreshes.claim(resumeId, owner, token, true)).thenReturn(
            Optional.of(Map.of("generation", generation, "requested_profile_hash", saved.get("profile_hash")))
        );
        when(refreshes.complete(eq(resumeId), eq(owner), eq(token), eq(generation), any(UUID.class))).thenReturn(1);
        clearInvocations(ai);

        assertEquals(true, service.createForRefresh(resumeId, owner, token, generation).get("cached"));

        verify(refreshes).claim(resumeId, owner, token, false);
        verify(refreshes).claim(resumeId, owner, token, true);
        verify(refreshes).complete(resumeId, owner, token, generation, (UUID) saved.get("id"));
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void anExpiredRefreshClaimCannotReuseRedis() {
        service.create(resumeId);
        clearInvocations(values, ai);

        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.createForRefresh(resumeId, owner, UUID.randomUUID(), 1))
                .status()
        );

        verify(values, never()).get(anyString());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void redisPublicationWaitsForCommitAndRollbackDoesNotPublish() {
        TransactionSynchronizationManager.initSynchronization();

        service.create(resumeId);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertTrue(cache.isEmpty());
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.create(resumeId);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, cache.size());
        verify(values).set(
            anyString(),
            anyString(),
            argThat(
                (
                    Duration ttl) -> !ttl.isZero() && !ttl.isNegative() && ttl.compareTo(Duration.ofHours(24)) <= 0
            )
        );
    }

    private Map<String, Object> generated(
        Map<String, Object> input) {
        Map<?, ?> cv = (Map<?, ?>) input.get("resume");
        Map<?, ?> career = (Map<?, ?>) input.get("career_profile");
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (String code : ResumeProfileAlignment.CODES) {
            boolean assessed = code.equals("TARGET_ROLE");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", assessed ? "ASSESSED" : "INSUFFICIENT_EVIDENCE");
            row.put("score", assessed ? 80 : null);
            row.put("feedback", "Compare only the reviewed evidence.");
            row.put("profile_evidence", assessed ? List.of(career.get("target_job_title")) : List.of());
            row.put(
                "cv_evidence",
                assessed ? List.of(((Map<?, ?>) cv.get("structured_data")).get("summary")) : List.of()
            );
            row.put("suggestions", List.of());
            criteria.add(row);
        }
        return new LinkedHashMap<>(
            Map.of("rubric_version", ResumeProfileAlignment.RUBRIC_VERSION, "criteria", criteria, "_model", ai.model())
        );
    }
}
