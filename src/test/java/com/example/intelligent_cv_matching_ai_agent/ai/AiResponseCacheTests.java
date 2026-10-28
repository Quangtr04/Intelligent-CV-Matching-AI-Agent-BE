package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiResponseCache;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
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
class AiResponseCacheTests {

    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final AiResponseCache cache = new AiResponseCache(provider);

    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, String> identity = Map.of("user_id", UUID.randomUUID().toString(), "input_hash", "hash");

    private final Duration ttl = Duration.ofHours(1);

    @BeforeEach
    void configure() {
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(values);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void roundTripPreservesJsonContractAndReturnsAnIndependentMutableResponse() {
        UUID id = UUID.randomUUID();
        OffsetDateTime created = OffsetDateTime.parse("2026-10-28T08:00:00+07:00");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("created_at", created);
        response.put("ats_score", 80);
        response.put("suggestions", List.of(Map.of("text", "Describe Java experience")));
        doAnswer(call -> {
            when(values.get("key")).thenReturn(call.getArgument(1));
            return null;
        }).when(values).set(eq("key"), anyString(), any(Duration.class));

        cache.putAfterCommit("key", identity, response, ttl);
        Map<String, Object> result = cache.read("key", identity).orElseThrow();

        assertEquals(json.readTree(json.writeValueAsString(response)), json.valueToTree(result));
        assertEquals(id.toString(), result.get("id"));
        result.put("cached", true);
        assertFalse(cache.read("key", identity).orElseThrow().containsKey("cached"));
        verify(values).set(
            eq("key"),
            anyString(),
            argThat(
                (
                    Duration value) -> !value.isNegative() && value.compareTo(ttl) <= 0
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "not-json",
            "\"a-legacy-analysis-id\"",
            "[]",
            "{}",
            "{\"schema_version\":2,\"identity\":{},\"response\":{\"id\":\"id\"}}",
            "{\"schema_version\":1,\"identity\":{},\"response\":[]}",
            "{\"schema_version\":1,\"response\":{\"id\":\"id\"}}"}
    )
    void malformedAndLegacyPayloadsAreMisses(
        String payload) {
        when(values.get("key")).thenReturn(payload);

        assertTrue(cache.read("key", identity).isEmpty());
    }

    @Test
    void mismatchedIdentityCannotReturnAnotherUsersResponse() {
        when(values.get("key"))
            .thenReturn(envelope(Map.of("user_id", "other", "input_hash", "hash"), Map.of("id", "id")));

        assertTrue(cache.read("key", identity).isEmpty());
    }

    @Test
    void missingOrEmptyResponsesAreMissesEvenWhenTheIdentityMatches() {
        when(values.get("key")).thenReturn(envelope(identity, Map.of()));

        assertTrue(cache.read("key", identity).isEmpty());
        when(values.get("key")).thenReturn(json.writeValueAsString(Map.of("schema_version", 1, "identity", identity)));
        assertTrue(cache.read("key", identity).isEmpty());
    }

    @Test
    void connectionAndCommandTimeoutFailuresBecomeMisses() {
        when(values.get("key")).thenThrow(new RedisConnectionFailureException("unavailable"));
        assertTrue(cache.read("key", identity).isEmpty());

        doThrow(new QueryTimeoutException("command timeout")).when(values).get("key");
        assertTrue(cache.read("key", identity).isEmpty());
    }

    @Test
    void unavailableRedisProviderIsOptional() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertTrue(cache.read("key", identity).isEmpty());
        assertDoesNotThrow(() -> cache.putAfterCommit("key", identity, Map.of("id", "id"), ttl));
        assertTrue(new AiResponseCache(null).read("key", identity).isEmpty());
        assertDoesNotThrow(() -> new AiResponseCache(null).putAfterCommit("key", identity, Map.of("id", "id"), ttl));
        verifyNoInteractions(values);
    }

    @Test
    void writesWaitForCommitAndCaptureTheResponseBeforeItCanBeMutated() {
        TransactionSynchronizationManager.initSynchronization();
        Map<String, Object> response = new LinkedHashMap<>(Map.of("id", "id", "cached", false));

        cache.putAfterCommit("key", identity, response, ttl);
        response.put("cached", true);
        verifyNoInteractions(values);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(values).set(
            eq("key"),
            argThat(payload -> Boolean.FALSE.equals(decodedResponse(payload).get("cached"))),
            any(Duration.class)
        );
    }

    @Test
    void rollbackNeverPublishesAnAnalysisThatWasNotCommitted() {
        TransactionSynchronizationManager.initSynchronization();

        cache.putAfterCommit("key", identity, Map.of("id", "id"), ttl);
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(values);
    }

    @Test
    void cacheWriteFailureCannotFailTheBusinessResultOrCommitCallback() {
        doThrow(new QueryTimeoutException("timeout")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertDoesNotThrow(() -> cache.putAfterCommit("key", identity, Map.of("id", "id"), ttl));

        TransactionSynchronizationManager.initSynchronization();
        cache.putAfterCommit("key", identity, Map.of("id", "id"), ttl);
        assertDoesNotThrow(
            () -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)
        );
    }

    @Test
    void invalidTtlAndUnserializableResponsesDoNotWrite() {
        cache.putAfterCommit("key", identity, Map.of("id", "id"), Duration.ZERO);
        cache.putAfterCommit("key", identity, Map.of("id", "id"), Duration.ofSeconds(-1));
        Map<String, Object> recursive = new LinkedHashMap<>();
        recursive.put("self", recursive);
        assertDoesNotThrow(() -> cache.putAfterCommit("key", identity, recursive, ttl));

        verifyNoInteractions(values);
    }

    private String envelope(
        Map<String, String> expectedIdentity,
        Map<String, Object> response) {
        return json.writeValueAsString(Map.of("schema_version", 1, "identity", expectedIdentity, "response", response));
    }

    private Map<String, Object> decodedResponse(
        String payload) {
        return (Map<String, Object>) ((Map<?, ?>) json.readValue(payload, Map.class)).get("response");
    }
}
