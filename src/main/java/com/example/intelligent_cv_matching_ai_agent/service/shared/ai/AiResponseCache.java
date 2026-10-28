package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Optional response cache. PostgreSQL remains authoritative when Redis is unavailable. */
public final class AiResponseCache {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int SCHEMA_VERSION = 1;

    private final ObjectProvider<StringRedisTemplate> redis;

    public AiResponseCache(
        ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> read(
        String key,
        Map<String, String> identity) {
        if (redis == null || key == null || key.isBlank() || identity == null || identity.isEmpty())
            return Optional.empty();

        try {
            StringRedisTemplate client = redis.getIfAvailable();
            if (client == null)
                return Optional.empty();

            String value = client.opsForValue().get(key);
            if (value == null)
                return Optional.empty();

            Object decoded = JSON.readValue(value, Object.class);
            if (!(decoded instanceof Map<?, ?> envelope)
                || !Integer.valueOf(SCHEMA_VERSION).equals(envelope.get("schema_version"))
                || !identity.equals(envelope.get("identity"))
                || !(envelope.get("response") instanceof Map<?, ?> response) || response.isEmpty())
                return Optional.empty();

            return Optional.of(new LinkedHashMap<>((Map<String, Object>) response));
        } catch (DataAccessException | JacksonException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public void putAfterCommit(
        String key,
        Map<String, String> identity,
        Map<String, Object> response,
        Duration ttl) {
        if (redis == null || key == null || key.isBlank() || identity == null || identity.isEmpty() || response == null
            || response.isEmpty() || ttl == null || ttl.isNegative() || ttl.isZero())
            return;

        final String snapshot;
        final long prepared = System.nanoTime();
        try {
            snapshot = JSON.writeValueAsString(
                Map.of("schema_version", SCHEMA_VERSION, "identity", identity, "response", response)
            );
        } catch (JacksonException | IllegalArgumentException ex) {
            return;
        }

        Runnable publish = () -> {
            Duration remaining = ttl.minusNanos(System.nanoTime() - prepared);
            if (remaining.isNegative() || remaining.isZero())
                return;

            try {
                StringRedisTemplate client = redis.getIfAvailable();
                if (client != null)
                    client.opsForValue().set(key, snapshot, remaining);
            } catch (DataAccessException | IllegalArgumentException ex) {
                // An optional cache must not turn a committed business operation into a failure.
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
