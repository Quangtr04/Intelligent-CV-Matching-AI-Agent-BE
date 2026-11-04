package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiConfigurationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.ai.AiPromptRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAiManagementTests {

    private final AdminRepository db = mock(AdminRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AuditService audit = mock(AuditService.class);

    private final AiService ai = mock(AiService.class);

    private final UUID actor = UUID.randomUUID();

    private final AdminService service =
        new AdminService(db, user, audit, mock(TokenService.class), mock(PasswordEncoder.class), ai);

    @BeforeEach
    void identity() {
        when(user.id()).thenReturn(actor);
        when(db.json(any())).thenReturn("json");
        when(ai.provider()).thenReturn("gemini");
    }

    @Test
    void runtimeAndFeatureCatalogRequireAdminBeforeReadingConfiguration() {
        doThrow(ApiException.forbidden("Administrator access required")).when(user).requireRole("ADMIN");
        assertEquals(403, assertThrows(ApiException.class, service::runtimeConfiguration).status());
        assertEquals(403, assertThrows(ApiException.class, service::features).status());
        verifyNoInteractions(db, ai, audit);
    }

    @Test
    void runtimeDisplaysTheEffectiveSettingsWithoutCreatingDatabaseOverrides() {
        Map<String, Object> effective =
            Map.of("provider", "gemini", "chat", Map.of("modelName", "gemini-2.5-flash-lite", "source", "ENVIRONMENT"));
        when(ai.runtimeConfiguration()).thenReturn(effective);
        assertSame(effective, service.runtimeConfiguration());
        verify(user).requireRole("ADMIN");
        verifyNoInteractions(db, audit);
    }

    @Test
    void catalogContainsSupportedFeaturesAndExposesTheNonEditableCvRequirements() {
        List<Map<String, Object>> features = service.features();
        assertEquals(features.size(), features.stream().map(row -> row.get("featureCode")).distinct().count());
        Map<String, Object> cv =
            features.stream().filter(row -> "CV_ANALYZE".equals(row.get("featureCode"))).findFirst().orElseThrow();
        assertEquals(4000, cv.get("minOutputTokens"));
        assertEquals(true, cv.get("deterministic"));
        assertTrue(cv.get("responseSchema").toString().contains("writing_quality"));
        Map<String, Object> support =
            features.stream().filter(row -> "CV_AI_SUPPORT".equals(row.get("featureCode"))).findFirst().orElseThrow();
        assertEquals(2000, support.get("minOutputTokens"));
        assertEquals("CAREER_CHAT", AiFeatureCatalog.requireSupported(" career_chat "));
        verifyNoInteractions(db, ai, audit);
    }

    @Test
    void savesCanonicalRuntimeOptionsAsANewActiveAuditedVersion() {
        String provider = "gemini";
        String model = "gemini-2.5-flash-lite";
        UUID created = UUID.randomUUID();
        Map<String, Object> after = Map.of("id", created, "version", 7, "is_active", true);
        when(db.getNextAiConfigurationVersion("CHAT")).thenReturn(7);
        when(db.insertAiConfigurations(anyMap())).thenReturn(created);
        when(db.getAiConfigurationsById(created)).thenReturn(after);

        assertSame(
            after,
            service.configure(
                new AiConfigurationRequest(
                    "CHAT",
                    provider.toUpperCase(java.util.Locale.ROOT),
                    " " + model + " ",
                    Map.of("temperature", 0.3, "maxOutputTokens", 3000)
                )
            )
        );

        verify(db).json(
            argThat(
                value -> value instanceof Map<?, ?> map && Double.valueOf(0.3).equals(map.get("temperature"))
                    && Integer.valueOf(3000).equals(map.get("numPredict")) && !map.containsKey("maxOutputTokens")
            )
        );
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(db).insertAiConfigurations(fields.capture());
        assertEquals(provider, fields.getValue().get("provider"));
        assertEquals(model, fields.getValue().get("model_name"));
        assertEquals(7, fields.getValue().get("version"));
        assertEquals(true, fields.getValue().get("is_active"));
        var order = inOrder(db);
        order.verify(db).lockAiConfigurationType("CHAT");
        order.verify(db).getNextAiConfigurationVersion("CHAT");
        order.verify(db).updateAiConfigurationsByConfigType("CHAT");
        order.verify(db).insertAiConfigurations(anyMap());
        verify(audit).record("CONFIGURE_AI", "AI_CONFIGURATION", created, null, after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"embedding", "provider", "unsupported", "parameter", "tokenBound", "context", "reasoning"})
    void invalidRuntimeConfigurationCannotDeactivateAnActiveVersion(
        String scenario) {
        String type =
            "embedding".equals(scenario) ? "EMBEDDING" : "unsupported".equals(scenario) ? "CUSTOM_MODEL" : "CHAT";
        String provider = "provider".equals(scenario) ? "unknown" : "gemini";
        Map<String, Object> configuration = switch (scenario) {
            case "parameter" -> Map.of("api_key", "credential");
            case "tokenBound" -> Map.of("numPredict", 1000000);
            case "context" -> Map.of("numCtx", 8192);
            case "reasoning" -> Map.of("think", true);
            default -> Map.of();
        };
        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.configure(new AiConfigurationRequest(type, provider, "gemini-2.5-flash-lite", configuration))
        );
        assertEquals("embedding".equals(scenario) ? 409 : 400, failure.status());
        verifyNoInteractions(db, ai, audit);
    }

    @Test
    void unsupportedPromptCodeCannotCreateOrRestoreAnActivePrompt() {
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.prompt(
                    new AiPromptRequest("UNIMPLEMENTED_FEATURE", "Prompt", "Instructions", "Output instructions")
                )
            ).status()
        );
        UUID old = UUID.randomUUID();
        when(db.getAiPromptTemplatesById(old)).thenReturn(Map.of("feature_code", "UNIMPLEMENTED_FEATURE"));
        assertEquals(400, assertThrows(ApiException.class, () -> service.restorePrompt(old)).status());
        verify(db, never()).lockAiPromptFeatureCode(anyString());
        verify(db, never()).updateAiPromptTemplatesByFeatureCode(any());
        verify(db, never()).insertAiPromptTemplates(anyMap());
        verifyNoInteractions(ai, audit);
    }

    @Test
    void supportedPromptKeepsVersioningAndAuditWhileUsingTheRegisteredFeatureCode() {
        UUID created = UUID.randomUUID();
        Map<String, Object> after = Map.of("id", created, "version", 3, "is_active", true);
        when(db.getNextAiPromptVersion("CV_AI_SUPPORT")).thenReturn(3);
        when(db.insertAiPromptTemplates(anyMap())).thenReturn(created);
        when(db.getAiPromptTemplatesById(created)).thenReturn(after);
        assertSame(
            after,
            service.prompt(
                new AiPromptRequest(
                    "CV_AI_SUPPORT",
                    "Hỗ trợ CV",
                    "Nhận xét ngắn gọn và đúng dữ liệu.",
                    "Trả đề xuất để người dùng xem lại."
                )
            )
        );
        var order = inOrder(db);
        order.verify(db).lockAiPromptFeatureCode("CV_AI_SUPPORT");
        order.verify(db).getNextAiPromptVersion("CV_AI_SUPPORT");
        order.verify(db).updateAiPromptTemplatesByFeatureCode("CV_AI_SUPPORT");
        order.verify(db).insertAiPromptTemplates(
            argThat(
                fields -> Integer.valueOf(3).equals(fields.get("version"))
                    && "CV_AI_SUPPORT".equals(fields.get("feature_code"))
                    && Boolean.TRUE.equals(fields.get("is_active"))
            )
        );
        verify(audit).record("UPDATE_PROMPT", "AI_PROMPT", created, null, after);
    }

    @Test
    void restoringAnEmbeddingVersionCannotActivateItOrChangeTheCurrentChatConfiguration() {
        UUID old = UUID.randomUUID();
        when(db.getAiConfigurationsById(old)).thenReturn(
            Map.of(
                "config_type",
                "EMBEDDING",
                "provider",
                "gemini",
                "model_name",
                "embedding-model",
                "config_json",
                Map.of()
            )
        );
        assertEquals(409, assertThrows(ApiException.class, () -> service.restoreConfiguration(old)).status());
        verify(db, never()).lockAiConfigurationType(anyString());
        verify(db, never()).updateAiConfigurationsByConfigType(any());
        verify(db, never()).insertAiConfigurations(anyMap());
        verifyNoInteractions(ai, audit);
    }

    @Test
    void restoringAValidVersionCreatesANewAuditedVersionWithoutOverwritingHistory() {
        String provider = "gemini";
        String model = "gemini-2.5-flash-lite";
        UUID old = UUID.randomUUID(), restored = UUID.randomUUID();
        Map<String, Object> original = Map.of(
            "id",
            old,
            "config_type",
            "CHAT",
            "provider",
            provider,
            "model_name",
            model,
            "config_json",
            Map.of("maxTokens", 3000, "num_ctx", 8192, "think", false),
            "version",
            2,
            "is_active",
            false
        );
        Map<String, Object> after = Map.of("id", restored, "version", 9, "is_active", true);
        when(db.getAiConfigurationsById(old)).thenReturn(original);
        when(db.getNextAiConfigurationVersion("CHAT")).thenReturn(9);
        when(db.insertAiConfigurations(anyMap())).thenReturn(restored);
        when(db.getAiConfigurationsById(restored)).thenReturn(after);

        assertSame(after, service.restoreConfiguration(old));

        verify(db).json(
            argThat(
                value -> value instanceof Map<?, ?> map && Integer.valueOf(3000).equals(map.get("numPredict"))
                    && !map.containsKey("maxTokens") && !map.containsKey("numCtx") && !map.containsKey("think")
            )
        );
        verify(db).insertAiConfigurations(
            argThat(
                fields -> Integer.valueOf(9).equals(fields.get("version"))
                    && Boolean.TRUE.equals(fields.get("is_active")) && !fields.containsKey("id")
                    && provider.equals(fields.get("provider")) && model.equals(fields.get("model_name"))
            )
        );
        assertEquals(2, original.get("version"));
        assertEquals(false, original.get("is_active"));
        verify(audit).record("RESTORE_AI_CONFIGURATION", "AI_CONFIGURATION", restored, original, after);
    }

    @Test
    void configurationForAnotherProviderCannotDeactivateTheConfiguredProvider() {
        when(ai.provider()).thenReturn("gemini");
        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.configure(new AiConfigurationRequest("CHAT", "openai", "previous-model", Map.of()))
        );
        assertEquals(400, failure.status());
        verifyNoInteractions(db, audit);
    }

    @Test
    void restoringAnOldProviderCannotDeactivateTheConfiguredProvider() {
        when(ai.provider()).thenReturn("gemini");
        UUID old = UUID.randomUUID();
        when(db.getAiConfigurationsById(old)).thenReturn(
            Map.of("config_type", "CHAT", "provider", "openai", "model_name", "old-model", "config_json", Map.of())
        );
        assertEquals(400, assertThrows(ApiException.class, () -> service.restoreConfiguration(old)).status());
        verify(db, never()).lockAiConfigurationType(anyString());
        verify(db, never()).updateAiConfigurationsByConfigType(any());
        verify(db, never()).insertAiConfigurations(anyMap());
        verifyNoInteractions(audit);
    }

    @Test
    void restoringUnsupportedSavedParametersCannotDeactivateTheActiveConfiguration() {
        UUID old = UUID.randomUUID();
        when(db.getAiConfigurationsById(old)).thenReturn(
            Map.of(
                "config_type",
                "CHAT",
                "provider",
                "gemini",
                "model_name",
                "gemini-2.5-flash-lite",
                "config_json",
                Map.of("api_key", "credential")
            )
        );
        assertEquals(400, assertThrows(ApiException.class, () -> service.restoreConfiguration(old)).status());
        verify(db, never()).lockAiConfigurationType(anyString());
        verify(db, never()).updateAiConfigurationsByConfigType(any());
        verify(db, never()).insertAiConfigurations(anyMap());
        verifyNoInteractions(ai, audit);
    }
}
