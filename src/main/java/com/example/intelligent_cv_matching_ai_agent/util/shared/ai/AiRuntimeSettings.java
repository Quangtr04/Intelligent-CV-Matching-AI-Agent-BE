package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.Values;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Validated, effective model settings shared by administration, generation and cache identity. */
public record AiRuntimeSettings(
    String configType,
    String provider,
    String modelName,
    Map<String, Object> configuration,
    Map<String, Object> overrides,
    String source,
    Object version
) {

    public static AiRuntimeSettings resolve(
        String type,
        String provider,
        String defaultModel,
        Map<String, Object> defaults,
        Optional<Map<String, Object>> stored) {
        Map<String, Object> overrides = stored.map(row -> {
            Map<String, Object> values = new LinkedHashMap<>(Values.object(row.get("config_json")));
            // Older saved configurations can contain controls no longer used by the API client.
            values.keySet().removeAll(java.util.Set.of("numCtx", "num_ctx", "contextWindow", "contextLength", "think"));
            return normalizeConfiguration(type, values);
        }).orElse(Map.of());
        Map<String, Object> configuration = new LinkedHashMap<>(defaults);
        configuration.putAll(overrides);
        String selected = stored.map(row -> java.util.Objects.toString(row.get("model_name"), "").strip())
            .filter(value -> !value.isBlank()).orElse(defaultModel);
        return new AiRuntimeSettings(
            type,
            provider,
            selected,
            Collections.unmodifiableMap(configuration),
            overrides,
            stored.isPresent() ? "DATABASE" : "ENVIRONMENT",
            stored.map(row -> row.get("version")).orElse(null)
        );
    }

    public static Map<String, Object> normalizeConfiguration(
        String type,
        Map<String, Object> values) {
        String normalizedType = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        if ("EMBEDDING".equals(normalizedType)) {
            throw new ApiException(
                409,
                "Embedding configuration is read-only; changing its model requires vector reindexing"
            );
        }
        if (!java.util.Set.of("CHAT", "ASSESSMENT").contains(normalizedType)) {
            throw ApiException.badRequest("Supported editable AI configuration types: CHAT, ASSESSMENT");
        }
        if (values == null) {
            throw ApiException.badRequest("AI configuration must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = switch (entry.getKey()) {
                case "temperature" -> "temperature";
                case "numPredict", "num_predict", "maxOutputTokens", "maxTokens", "max_output_tokens" -> "numPredict";
                default -> throw ApiException.badRequest("Unsupported AI parameter: " + entry.getKey());
            };
            if (result.containsKey(key)) {
                throw ApiException.badRequest("Specify each AI parameter only once: " + key);
            }
            Object value = entry.getValue();
            if ("temperature".equals(key)) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > 2) {
                    throw ApiException.badRequest("temperature must be between 0 and 2");
                }
                value = ((Number) value).doubleValue();
            } else {
                int minimum = 256;
                int maximum = 16000;
                try {
                    if (!(value instanceof Number)) {
                        throw new ArithmeticException();
                    }
                    int integer = new BigDecimal(value.toString()).intValueExact();
                    if (integer < minimum || integer > maximum) {
                        throw new ArithmeticException();
                    }
                    value = integer;
                } catch (ArithmeticException | NumberFormatException invalid) {
                    throw ApiException.badRequest(key + " must be an integer from " + minimum + " to " + maximum);
                }
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configType", configType);
        result.put("provider", provider);
        result.put("modelName", modelName);
        result.put("configuration", configuration);
        result.put("source", source);
        result.put("version", version);
        return result;
    }
}
