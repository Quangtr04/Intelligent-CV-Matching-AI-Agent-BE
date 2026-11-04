package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiRuntimeSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeSettingsTests {

    @Test
    void mergesOnlyExplicitOverridesAndReportsTheActualConfigurationSource() {
        Map<String, Object> defaults = Map.of("temperature", 0.2, "numPredict", 2000);
        var original = AiRuntimeSettings.resolve("CHAT", "openai", "default-model", defaults, Optional.empty());
        assertEquals("ENVIRONMENT", original.source());
        assertEquals(defaults, original.configuration());
        var updated = AiRuntimeSettings.resolve(
            "CHAT",
            "openai",
            "default-model",
            defaults,
            Optional
                .of(Map.of("model_name", " other-model ", "version", 3, "config_json", Map.of("maxOutputTokens", 3000)))
        );
        assertEquals("other-model", updated.modelName());
        assertEquals("DATABASE", updated.source());
        assertEquals(3, updated.version());
        assertEquals(Map.of("temperature", 0.2, "numPredict", 3000), updated.configuration());
        assertThrows(UnsupportedOperationException.class, () -> updated.configuration().put("temperature", 1));
        assertEquals(2000, defaults.get("numPredict"));
    }

    @Test
    void savedObsoleteControlsDoNotPreventLoadingTheValidApiSettings() {
        var settings = AiRuntimeSettings.resolve(
            "ASSESSMENT",
            "openai",
            "default-model",
            Map.of("temperature", 0.2, "numPredict", 2000),
            Optional.of(
                Map.of(
                    "model_name",
                    "saved-model",
                    "config_json",
                    Map.of(
                        "maxTokens",
                        3000,
                        "numCtx",
                        8192,
                        "num_ctx",
                        8192,
                        "contextWindow",
                        8192,
                        "contextLength",
                        8192,
                        "think",
                        true
                    )
                )
            )
        );
        assertEquals("saved-model", settings.modelName());
        assertEquals(Map.of("temperature", 0.2, "numPredict", 3000), settings.configuration());
        assertEquals(Map.of("numPredict", 3000), settings.overrides());
    }

    @Test
    void rejectsInvalidAndAmbiguousValuesInsteadOfSilentlyIgnoringThem() {
        List<Map<String, Object>> invalid = List.of(
            Map.of("temperature", Double.NaN),
            Map.of("temperature", 2.1),
            Map.of("numCtx", 1024),
            Map.of("numPredict", 100.5),
            Map.of("numPredict", "2000"),
            Map.of("top_k", 10),
            Map.of("numPredict", 4096, "maxTokens", 8192),
            Map.of("contextWindow", 8192),
            Map.of("think", true)
        );
        for (Map<String, Object> values : invalid) {
            assertEquals(
                400,
                assertThrows(ApiException.class, () -> AiRuntimeSettings.normalizeConfiguration("CHAT", values))
                    .status()
            );
        }
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> AiRuntimeSettings.normalizeConfiguration("ASSESSMENT", Map.of("think", false))
            ).status()
        );
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> AiRuntimeSettings.normalizeConfiguration("EMBEDDING", Map.of()))
                .status()
        );
    }
}
