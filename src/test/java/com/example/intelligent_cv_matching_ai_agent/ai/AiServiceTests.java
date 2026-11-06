package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.AiRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiUsageRecorder;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.GeminiTokenCounter;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServiceTests {

    @Test
    void nativePromptCountReservesInputPlusBoundedOutputInsteadOfUtf8Bytes() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(db.optional(contains("config_type='CHAT'"), eq("gemini"))).thenReturn(
            Optional.of(Map.of("model_name", "gemini-2.5-flash-lite", "config_json", Map.of("numPredict", 1500)))
        );
        when(counter.countPrompt(eq("gemini-2.5-flash-lite"), any(Prompt.class))).thenReturn(500);
        when(entitlement.reserve(actor, "CAREER_CHAT", 2128)).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(500, 100)).build()
            )
        );
        AiService configured = service(true, "test-assessment");
        configured.configureGeminiTokenCounter(counter);
        assertEquals("Hello", configured.generate("CAREER_CHAT", Map.of("message", "Hello")).get("answer"));
        var calls = inOrder(counter, entitlement, chat);
        calls.verify(counter).countPrompt(eq("gemini-2.5-flash-lite"), any(Prompt.class));
        calls.verify(entitlement).reserve(actor, "CAREER_CHAT", 2128);
        calls.verify(chat).call(any(Prompt.class));
        calls.verify(entitlement).complete(reservation, 600);
    }

    @Test
    void geminiChatAppliesActiveModelSettingsWithJsonModeAndExistingQuotaAccounting() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(db.optional(contains("config_type='CHAT'"), eq("gemini"))).thenReturn(
            Optional.of(
                Map.of(
                    "model_name",
                    "gemini-2.5-flash-lite",
                    "config_json",
                    Map.of("temperature", 0.4, "numPredict", 1500)
                )
            )
        );
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 6)).build()
            )
        );

        AiService service = service(true, "test-assessment");
        service.configureRequestTimeout(java.time.Duration.ofSeconds(7));
        service.configureReasoningEffort("none");
        assertEquals("Hello", service.generate("CAREER_CHAT", Map.of("message", "Hello")).get("answer"));

        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> prompt.getOptions() instanceof OpenAiChatOptions options
                        && "gemini-2.5-flash-lite".equals(options.getModel())
                        && Integer.valueOf(1500).equals(options.getMaxTokens())
                        && Double.valueOf(0.4).equals(options.getTemperature()) && options.getMaxRetries() == 0
                        && java.time.Duration.ofSeconds(7).equals(options.getTimeout())
                        && "none".equals(options.getReasoningEffort())
                        && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
            )
        );
        verify(entitlement).complete(reservation, 26);
        verify(recorder).record(
            argThat(
                row -> "gemini".equals(row.get("provider")) && "gemini-2.5-flash-lite".equals(row.get("model_name"))
                    && Boolean.TRUE.equals(row.get("success"))
            )
        );
    }

    @Test
    void geminiEmbeddingRequestsDatabaseCompatibleDimensionsAndChargesReportedUsage() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(validEmbeddingVector(), 0)),
                new EmbeddingResponseMetadata("test-embedding", new DefaultUsage(4, 0))
            )
        );

        AiService service = service(true, "test-assessment");
        service.configureRequestTimeout(java.time.Duration.ofSeconds(9));
        assertEquals(1024, service.embed("Java").length);

        verify(embedding).call(
            argThat(
                request -> request.getOptions() instanceof OpenAiEmbeddingOptions options
                    && "test-embedding".equals(options.getModel())
                    && Integer.valueOf(1024).equals(options.getDimensions()) && options.getMaxRetries() == 0
                    && java.time.Duration.ofSeconds(9).equals(options.getTimeout())
            )
        );
        verify(entitlement).complete(reservation, 4);
        assertEquals("gemini", service.runtimeConfiguration().get("provider"));
    }

    @Test
    void thinkingTokensIncludedInTheProviderTotalAreChargedAndLogged() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 10, 160)).build()
            )
        );

        Map<String, Object> result = service(true).generate("CAREER_CHAT", Map.of("message", "Hello"));

        assertEquals(Map.of("input_tokens", 100, "output_tokens", 60, "total_tokens", 160), result.get("_usage"));
        verify(entitlement).complete(reservation, 160);
        verify(recorder).record(
            argThat(
                row -> "gemini".equals(row.get("provider")) && Integer.valueOf(100).equals(row.get("input_tokens"))
                    && Integer.valueOf(60).equals(row.get("output_tokens"))
                    && Integer.valueOf(160).equals(row.get("total_tokens"))
            )
        );
    }

    @Test
    void providerTotalCannotReduceTheSumOfReportedInputAndCompletionTokens() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 10, 105)).build()
            )
        );

        Map<String, Object> result = service(true).generate("CAREER_CHAT", Map.of("message", "Hello"));

        assertEquals(Map.of("input_tokens", 100, "output_tokens", 10, "total_tokens", 110), result.get("_usage"));
        verify(entitlement).complete(reservation, 110);
    }

    @Test
    void embeddingUsesTheNativeCountWhenCompatibleResponseOmitsUsage() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(counter.countText("test-embedding", "Java")).thenReturn(2);
        when(embedding.call(any(EmbeddingRequest.class)))
            .thenReturn(new EmbeddingResponse(List.of(new Embedding(validEmbeddingVector(), 0))));
        AiService configured = service(true);
        configured.configureGeminiTokenCounter(counter);

        assertEquals(1024, configured.embed("Java").length);

        var order = inOrder(counter, embedding);
        order.verify(embedding).call(any(EmbeddingRequest.class));
        order.verify(counter).countText("test-embedding", "Java");
        verify(entitlement).complete(reservation, 2);
        verify(recorder).record(
            argThat(
                row -> Integer.valueOf(2).equals(row.get("total_tokens")) && Boolean.TRUE.equals(row.get("success"))
            )
        );
    }

    @Test
    void embeddingIsNormalizedWithoutMutatingTheProviderVector() {
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        float[] providerVector = new float[1024];
        providerVector[0] = 3;
        providerVector[1] = 4;
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(providerVector, 0)),
                new EmbeddingResponseMetadata("test-embedding", new DefaultUsage(2, 0))
            )
        );

        float[] normalized = service(true).embed("Java");

        assertNotSame(providerVector, normalized);
        assertEquals(0.6, normalized[0], 1e-6);
        assertEquals(0.8, normalized[1], 1e-6);
        assertEquals(1.0, (double) normalized[0] * normalized[0] + (double) normalized[1] * normalized[1], 1e-6);
        assertEquals(3, providerVector[0]);
        assertEquals(4, providerVector[1]);
    }

    @Test
    void zeroEmbeddingIsRejectedAfterAccountingForTheReceivedProviderCall() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(new float[1024], 0)),
                new EmbeddingResponseMetadata("test-embedding", new DefaultUsage(2, 0))
            )
        );

        assertEquals(502, assertThrows(ApiException.class, () -> service(true).embed("Java")).status());

        verify(entitlement).complete(reservation, 2);
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(argThat(row -> Boolean.FALSE.equals(row.get("success"))));
    }

    private static float[] validEmbeddingVector() {
        float[] vector = new float[1024];
        vector[0] = 1;
        return vector;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT21M"})
    void invalidGeminiRequestTimeoutIsRejectedBeforeCallingTheProvider(
        String timeout) {
        AiService service = service(true, "test-assessment");
        assertThrows(
            IllegalArgumentException.class,
            () -> service.configureRequestTimeout(java.time.Duration.parse(timeout))
        );
        verifyNoInteractions(chats, embeddings, entitlement);
    }

    @Test
    void geminiStillRejectsInvalidTaskJsonAndSettlesOnlyOneProviderCall() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 6)).build()
            )
        );

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service(true, "test-assessment").generate("CAREER_CHAT", Map.of("message", "Hi"))
        );

        assertEquals(502, failure.status());
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).complete(reservation, 26);
    }

    @Test
    void geminiCallTimeoutReturnsGatewayTimeoutAndReleasesTheReservation() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenThrow(
            new RuntimeException("Synthetic transport failure", new java.io.InterruptedIOException("timeout"))
        );

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service(true, "test-assessment").generate("CAREER_CHAT", Map.of("message", "Hi"))
        );

        assertEquals(504, failure.status());
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).cancel(reservation);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    void providerHttpFailuresAreExplainedWithoutSpendingApplicationQuotaOrRetrying(
        int status) {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        OpenAIServiceException upstream = mock(OpenAIServiceException.class);
        when(upstream.statusCode()).thenReturn(status);
        when(upstream.getMessage()).thenReturn("Sensitive provider body and credentials must never be exposed");
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenThrow(new RuntimeException("Transport wrapper", upstream));

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service(true).generate("CAREER_CHAT", Map.of("message", "Synthetic question"))
        );

        assertEquals(status == 500 ? 502 : 503, failure.status());
        assertTrue(failure.getMessage().contains(status == 429 ? "request or token limit" : "HTTP " + status));
        assertFalse(failure.getMessage().contains("Sensitive"));
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).cancel(reservation);
        verify(entitlement, never()).complete(any(), anyInt());
        verify(recorder).record(
            argThat(
                row -> Boolean.FALSE.equals(row.get("success"))
                    && ("AI provider returned HTTP " + status).equals(row.get("error_message"))
            )
        );
    }

    @Test
    void applicationQuotaStillReturns429WithoutCallingTheProvider() {
        UUID actor = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt()))
            .thenThrow(new ApiException(429, "AI token quota exceeded"));

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service(true).generate("CAREER_CHAT", Map.of("message", "Synthetic question"))
        );

        assertEquals(429, failure.status());
        assertEquals("AI token quota exceeded", failure.getMessage());
        verify(chat, never()).call(any(Prompt.class));
        verify(entitlement, never()).cancel(any());
    }

    @Test
    void nativeEmbeddingTokenCountLimitReturnsProvider503BeforeReservingApplicationQuota() {
        UUID actor = UUID.randomUUID();
        GeminiTokenCounter counter = mock(GeminiTokenCounter.class);
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(counter.countText(anyString(), anyString())).thenThrow(
            org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests",
                org.springframework.http.HttpHeaders.EMPTY,
                "Sensitive upstream response".getBytes(),
                null
            )
        );
        AiService configured = service(true);
        configured.configureGeminiTokenCounter(counter);

        ApiException failure = assertThrows(ApiException.class, () -> configured.embed("Synthetic Java CV"));

        assertEquals(503, failure.status());
        assertTrue(failure.getMessage().contains("AI provider"));
        assertFalse(failure.getMessage().contains("Sensitive"));
        verifyNoInteractions(entitlement);
        verify(embedding, never()).call(any(EmbeddingRequest.class));
        verify(recorder).record(argThat(row -> "AI provider returned HTTP 429".equals(row.get("error_message"))));
    }

    @Test
    void profileAlignmentUsesJsonModeAndTheSameQuotaAccounting() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CV_PROFILE_ALIGNMENT"), anyInt())).thenReturn(reservation);
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (String code : com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment.CODES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", "NOT_APPLICABLE");
            row.put("score", null);
            row.put("feedback", "No profile information for this criterion.");
            row.put("profile_evidence", List.of());
            row.put("cv_evidence", List.of());
            row.put("suggestions", List.of());
            criteria.add(row);
        }
        String json = JsonMapper.builder().build()
            .writeValueAsString(Map.of("rubric_version", "cv-profile-alignment-v1", "criteria", criteria));
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 200)).build()
            )
        );
        service(true).generate("CV_PROFILE_ALIGNMENT", Map.of("resume", Map.of("summary", "Java")));
        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> {
                    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                    return Integer.valueOf(4000).equals(options.getMaxTokens())
                        && Double.valueOf(0.0).equals(options.getTemperature())
                        && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT;
                }
            )
        );
        verify(entitlement).complete(reservation, 300);
        verify(recorder)
            .record(
                argThat(
                    row -> "CV_PROFILE_ALIGNMENT".equals(row.get("feature_code"))
                        && Boolean.TRUE.equals(row.get("success"))
                )
            );
    }

    @Test
    void profileAlignmentRejectsOversizedContextBeforeReservingQuotaOrCallingTheModel() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true).generate("CV_PROFILE_ALIGNMENT", Map.of("resume", "x".repeat(17000)))
        );
        assertEquals(400, error.status());
        verifyNoInteractions(entitlement, chat, guests);
    }

    private final ObjectProvider<ChatModel> chats = mock(ObjectProvider.class);

    private final ObjectProvider<EmbeddingModel> embeddings = mock(ObjectProvider.class);

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final EntitlementService entitlement = mock(EntitlementService.class);

    private final GuestAccess guests = mock(GuestAccess.class);

    private final AiUsageRecorder recorder = mock(AiUsageRecorder.class);

    @Test
    void responseCacheFingerprintChangesWithActivePromptAndModelWithoutCallingAi() {
        AiService service = service(true);
        when(db.optional(contains("FROM ai_prompt_templates"), eq("JOB_ATS")))
            .thenReturn(Optional.of(Map.of("system_prompt", "Explain evidence", "user_prompt_template", "Be brief")));
        String first = service.cacheFingerprint("JOB_ATS");
        assertEquals(first, service.cacheFingerprint("JOB_ATS"));

        when(db.optional(contains("FROM ai_prompt_templates"), eq("JOB_ATS"))).thenReturn(
            Optional.of(Map.of("system_prompt", "Explain missing skills", "user_prompt_template", "Be brief"))
        );
        String changedPrompt = service.cacheFingerprint("JOB_ATS");
        assertNotEquals(first, changedPrompt);

        when(db.optional(contains("FROM ai_configurations"), eq("gemini")))
            .thenReturn(Optional.of(Map.of("model_name", "another-api-model")));
        assertNotEquals(changedPrompt, service.cacheFingerprint("JOB_ATS"));
        verifyNoInteractions(chats, embeddings, entitlement, guests, recorder);
    }

    @Test
    void responseCacheFingerprintSeparatesFeatures() {
        AiService service = service(true);
        assertNotEquals(service.cacheFingerprint("JOB_ATS"), service.cacheFingerprint("CV_ANALYZE"));
        verifyNoInteractions(chats, embeddings, entitlement, guests, recorder);
    }

    @Test
    void activeModelOptionsApplyToTheNextCallAndInvalidateTheResponseFingerprint() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}")))));
        var configuration = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>(
            Map.of(
                "model_name",
                "selected-chat",
                "version",
                1,
                "config_json",
                Map.of("temperature", 0.7, "numPredict", 1500)
            )
        );
        when(db.optional(contains("config_type='CHAT'"), eq("gemini")))
            .thenAnswer(call -> Optional.of(configuration.get()));
        AiService configured = service(true);
        String before = configured.cacheFingerprint("CAREER_CHAT");
        configured.generate("CAREER_CHAT", Map.of("message", "Hello"));
        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> {
                    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                    return "selected-chat".equals(options.getModel())
                        && Double.valueOf(0.7).equals(options.getTemperature())
                        && Integer.valueOf(1500).equals(options.getMaxTokens());
                }
            )
        );
        configuration.set(
            Map.of(
                "model_name",
                "selected-chat",
                "version",
                2,
                "config_json",
                Map.of("temperature", 0.1, "numPredict", 2500)
            )
        );
        assertNotEquals(before, configured.cacheFingerprint("CAREER_CHAT"));
        clearInvocations(chat);
        configured.generate("CAREER_CHAT", Map.of("message", "Hello"));
        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> {
                    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                    return Double.valueOf(0.1).equals(options.getTemperature())
                        && Integer.valueOf(2500).equals(options.getMaxTokens());
                }
            )
        );
        assertEquals("test-embedding", configured.embeddingModel());
        assertEquals(true, ((Map<?, ?>) configured.runtimeConfiguration().get("embedding")).get("readOnly"));
    }

    @Test
    void assessmentUsesItsOwnActiveSettingsInsteadOfTheGeneralChatOverride() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[]}")))));
        when(db.optional(contains("config_type='CHAT'"), eq("gemini")))
            .thenReturn(Optional.of(Map.of("model_name", "general-chat", "config_json", Map.of("numPredict", 500))));
        when(db.optional(contains("config_type='ASSESSMENT'"), eq("gemini"))).thenReturn(
            Optional.of(
                Map.of(
                    "model_name",
                    "selected-assessment",
                    "config_json",
                    Map.of("numPredict", 6000, "temperature", 0.1)
                )
            )
        );
        service(true).generate("ASSESSMENT_GENERATE", Map.of("jd_skills", List.of(Map.of("skill_name", "Java"))));
        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> {
                    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                    return "selected-assessment".equals(options.getModel())
                        && Integer.valueOf(6000).equals(options.getMaxTokens())
                        && Double.valueOf(0.1).equals(options.getTemperature())
                        && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT;
                }
            )
        );
    }

    @Test
    void cvSupportUsesItsOwnAdminPromptAndValidatesLiteralProposalsBeforeLoggingSuccess() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(db.optional(contains("FROM ai_prompt_templates"), eq("CV_AI_SUPPORT")))
            .thenReturn(Optional.of(Map.of("system_prompt", "Use concise CV instructions from administration.")));
        Map<String, Object> response = Map.of(
            "answer",
            "Built Java APIs with clear responsibilities.",
            "suggestions",
            List.of(
                Map.of(
                    "original_content",
                    "Built Java APIs.",
                    "suggested_content",
                    "Built Java APIs with clear responsibilities.",
                    "explanation",
                    "Clarify the actual responsibility."
                )
            )
        );
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(JsonMapper.builder().build().writeValueAsString(response))))
            )
        );
        var result = service(true).generate(
            "CV_AI_SUPPORT",
            Map.of("language", "en", "section", Map.of("field", "experience", "current_text", "Built Java APIs."))
        );
        assertEquals(response.get("answer"), result.get("answer"));
        verify(chat).call(
            argThat(
                (
                    Prompt prompt) -> {
                    OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
                    return Integer.valueOf(2000).equals(options.getMaxTokens())
                        && prompt.getInstructions().getFirst().getText()
                            .contains("Use concise CV instructions from administration.")
                        && prompt.getInstructions().getFirst().getText()
                            .contains("Response language: Respond in English.");
                }
            )
        );
        verify(recorder).record(
            argThat(row -> "CV_AI_SUPPORT".equals(row.get("feature_code")) && Boolean.TRUE.equals(row.get("success")))
        );
    }

    private AiService service(
        boolean enabled) {
        return service(enabled, "test-assessment");
    }

    private AiService service(
        boolean enabled,
        String assessmentModel) {
        return new AiService(
            chats,
            embeddings,
            JsonMapper.builder().build(),
            new AiRepository(db),
            user,
            entitlement,
            guests,
            recorder,
            enabled,
            "gemini",
            "test-chat",
            "test-embedding",
            1024,
            512,
            4000,
            4000,
            assessmentModel,
            0.2
        );
    }

    @Test
    void disabledModelCannotFabricateSuccessOrSpendQuota() {
        assertThrows(ApiException.class, () -> service(false).generate("CAREER_CHAT", Map.of("message", "hello")));
        verifyNoInteractions(entitlement, guests, recorder);
    }

    @Test
    void failedGuestReservationNeverRefundsPreviousUsage() {
        UUID guest = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(db.optional(anyString(), any())).thenReturn(Optional.empty());
        doThrow(new ApiException(429, "Trial exhausted")).when(guests)
            .reserve(eq(guest), anyString(), anyInt(), isNull());
        assertThrows(
            ApiException.class,
            () -> service(true).generateForGuest("CAREER_CHAT", Map.of("message", "hello"), guest)
        );
        verify(guests, never()).settle(any(UUID.class), anyInt(), anyBoolean());
        verifyNoInteractions(chat);
    }

    @Test
    void invalidJsonStillAccountsForACompletedProviderCall() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(db.optional(anyString(), any())).thenReturn(Optional.empty());
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("invalid-json")))));
        assertThrows(ApiException.class, () -> service(true).generate("CAREER_CHAT", Map.of("message", "hello")));
        verify(entitlement).complete(eq(reservation), intThat(n -> n > 0));
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(argThat(row -> Boolean.FALSE.equals(row.get("success"))));
    }

    @Test
    void emptyReceivedResponseIsChargedButTransportFailureIsCancelled() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertThrows(ApiException.class, () -> service(true).generate("CAREER_CHAT", Map.of("message", "hello")));
        verify(entitlement).complete(eq(reservation), intThat(n -> n > 0));
        clearInvocations(entitlement);
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(ApiException.class, () -> service(true).generate("CAREER_CHAT", Map.of("message", "hello")));
        verify(entitlement).cancel(reservation);
        verify(entitlement, never()).complete(any(), anyInt());
    }

    @Test
    void guestInvalidJsonSettlesTheCorrectReservationAsFailed() {
        UUID guest = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(guests.reserve(eq(guest), eq("CAREER_CHAT"), anyInt(), isNull())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("invalid"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(12, 5)).build()
            )
        );
        assertThrows(
            ApiException.class,
            () -> service(true).generateForGuest("CAREER_CHAT", Map.of("message", "hello"), guest)
        );
        verify(guests).settle(reservation, 17, false);
        verify(recorder).record(
            argThat(
                row -> guest.equals(row.get("guest_session_id")) && Integer.valueOf(17).equals(row.get("total_tokens"))
                    && Boolean.FALSE.equals(row.get("success"))
                    && "AI model returned invalid JSON".equals(row.get("error_message"))
            )
        );
    }

    @Test
    void configuredChatModelIsPassedToTheProviderAndActualUsageIsSettled() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(db.optional(contains("FROM ai_configurations"), eq("gemini")))
            .thenReturn(Optional.of(Map.of("model_name", "updated-model")));
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 6)).build()
            )
        );
        var result = service(true).generate("CAREER_CHAT", Map.of("message", "hello"));
        assertEquals("updated-model", result.get("_model"));
        verify(chat).call(
            org.mockito.ArgumentMatchers.<Prompt>argThat(
                prompt -> "updated-model".equals(prompt.getOptions().getModel())
                    && prompt.getOptions() instanceof OpenAiChatOptions options
                    && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
                    && prompt.getInstructions().getFirst().getText().contains("answer")
                    && Integer.valueOf(512).equals(options.getMaxTokens())
            )
        );
        verify(entitlement).complete(reservation, 26);
        verify(recorder).record(argThat(row -> "updated-model".equals(row.get("model_name"))));
    }

    @Test
    void cvAnalysisRequiresTheWritingRubricAfterAdminGuidanceAndPreservesCandidateLineBreaks() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(db.optional(contains("FROM ai_prompt_templates"), eq("CV_ANALYZE")))
            .thenReturn(Optional.of(Map.of("system_prompt", "Legacy guidance: return an overall_score only.")));
        when(entitlement.reserve(eq(actor), eq("CV_ANALYZE"), anyInt())).thenReturn(reservation);
        List<Map<String, Object>> criteria = List
            .of(
                "BULLET_STRUCTURE",
                "PUNCTUATION_CONSISTENCY",
                "PROFESSIONAL_WORDING",
                "CONCISENESS",
                "ACHIEVEMENT_CLARITY"
            ).stream()
            .map(
                code -> Map.<String, Object>of(
                    "code",
                    code,
                    "status",
                    "ASSESSED",
                    "score",
                    80,
                    "feedback",
                    "Clear descriptions with consistent punctuation.",
                    "evidence",
                    List.of("Built a Java API")
                )
            ).toList();
        Map<String, Object> response = Map.of(
            "content_score",
            85,
            "ats_score",
            75,
            "summary",
            "Review the wording.",
            "strengths",
            List.of("Specific technology"),
            "issues",
            List.of(),
            "suggestions",
            List.of(),
            "writing_quality",
            Map.of("rubric_version", "cv-writing-v1", "criteria", criteria, "limitations", List.of())
        );
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(JsonMapper.builder().build().writeValueAsString(response))))
            )
        );

        var result = service(true)
            .generate("CV_ANALYZE", Map.of("language", "en", "raw_text", "- Built a Java API\n- Wrote API tests"));

        assertEquals(85, result.get("content_score"));
        verify(chat).call(org.mockito.ArgumentMatchers.<Prompt>argThat(prompt -> {
            String system = prompt.getInstructions().getFirst().getText();
            String input = prompt.getInstructions().getLast().getText();
            return system.indexOf("Required evaluation rules:") > system.indexOf("Legacy guidance:")
                && system.contains("INSUFFICIENT_EVIDENCE") && system.contains("a final period is not mandatory")
                && system.contains("JSON delimiters/property names") && system.contains("Respond in English.")
                && system.endsWith(
                    com.example.intelligent_cv_matching_ai_agent.util.shared.ai.CvAnalysisLanguage.instructions("en")
                ) && prompt.getOptions() instanceof OpenAiChatOptions options
                && Integer.valueOf(4000).equals(options.getMaxTokens())
                && Double.valueOf(0.0).equals(options.getTemperature())
                && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
                && system.contains("writing_quality") && input.contains("- Built a Java API\\n- Wrote API tests");
        }));
        verify(entitlement).complete(eq(reservation), intThat(n -> n > 0));
    }

    @Test
    void legacyCvScoresWithoutWritingCriteriaAreRejectedAndAccountedAsAReceivedCall() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CV_ANALYZE"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"overall_score\":99,\"ats_score\":99,\"summary\":\"Good\","
                                + "\"strengths\":[],\"issues\":[],\"suggestions\":[]}"
                        )
                    )
                )
            )
        );

        assertThrows(ApiException.class, () -> service(true).generate("CV_ANALYZE", Map.of("raw_text", "Java")));
        verify(entitlement).complete(eq(reservation), intThat(n -> n > 0));
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(
            argThat(
                row -> Boolean.FALSE.equals(row.get("success"))
                    && ("AI model structured-output validation failed: content_score must be a finite number")
                        .equals(row.get("error_message"))
            )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"score", "language"})
    void invalidCvCriterionReportsTheActualValidationFailureWithoutRetrying(
        String failureKind) {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CV_ANALYZE"), anyInt())).thenReturn(reservation);
        Map<String, Object> report = com.example.intelligent_cv_matching_ai_agent.resume.ResumeWritingTestData.report();
        Map<String, Object> writing = (Map<String, Object>) report.get("writing_quality");
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) writing.get("criteria");
        if ("language".equals(failureKind)) {
            criteria.getFirst().put("feedback", "增加项目细节有助于展示候选人的能力。");
        } else {
            criteria.getFirst().put("status", "INSUFFICIENT_EVIDENCE");
        }
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage(JsonMapper.builder().build().writeValueAsString(report)))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(2050, 1865)).build()
            )
        );

        ApiException error =
            assertThrows(ApiException.class, () -> service(true).generate("CV_ANALYZE", Map.of("raw_text", "Java")));

        assertEquals(502, error.status());
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).complete(reservation, 3915);
        String detail = "language".equals(failureKind)
            ? "CV analysis contains Chinese text in generated commentary"
            : "unassessed writing criterion score must be null";
        verify(recorder).record(
            argThat(
                row -> Boolean.FALSE.equals(row.get("success")) && Integer.valueOf(3915).equals(row.get("total_tokens"))
                    && ("AI model structured-output validation failed: " + detail).equals(row.get("error_message"))
            )
        );
    }

    @Test
    void jdAssessmentTransmitsTheChosenSkillAllowlistAndItsOwnOutputBudget() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("ASSESSMENT_GENERATE"), anyInt())).thenReturn(reservation);
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int index = 0; index < 10; index++)
            questions.add(
                Map.of(
                    "part",
                    index < 5 ? 1 : 2,
                    "question_text",
                    "Docker question " + index,
                    "options",
                    Map.of("A", "First", "B", "Second", "C", "Third", "D", "Fourth"),
                    "correct_option",
                    "A",
                    "related_skill_name",
                    "Docker",
                    "jd_evidence",
                    "Maintain Docker containers",
                    "explanation",
                    "Use the documented container behavior."
                )
            );
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            JsonMapper.builder().build().writeValueAsString(Map.of("questions", questions))
                        )
                    )
                ),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 200)).build()
            )
        );

        var result = service(true).generate(
            "ASSESSMENT_GENERATE",
            Map.of("jd_text", "Maintain Docker containers", "jd_skills", List.of(Map.of("skill_name", "Docker")))
        );
        assertEquals("test-assessment", result.get("_model"));
        assertEquals(300, ((Map<?, ?>) result.get("_usage")).get("total_tokens"));
        verify(entitlement).complete(reservation, 300);
        verify(recorder).record(
            argThat(
                row -> "test-assessment".equals(row.get("model_name"))
                    && "ASSESSMENT_GENERATE".equals(row.get("feature_code"))
                    && Integer.valueOf(300).equals(row.get("total_tokens"))
            )
        );

        verify(chat).call(org.mockito.ArgumentMatchers.<Prompt>argThat(prompt -> {
            if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
                return false;
            return "test-assessment".equals(options.getModel()) && Integer.valueOf(4000).equals(options.getMaxTokens())
                && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
                && prompt.getInstructions().getLast().getText().contains("Docker")
                && prompt.getInstructions().getLast().getText().contains("jd_skills")
                && prompt.getInstructions().getFirst().getText().contains("ONE selected job description")
                && prompt.getInstructions().getFirst().getText().contains("Use established technical knowledge")
                && prompt.getInstructions().getFirst().getText().contains("questions 6-10 have part=2");
        }));
    }

    @Test
    void trimmedAssessmentModelAndItsOutputBudgetAreScopedToAssessmentOnly() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[]}")))));
        AiService configured = service(true, "  custom-assessment  ");
        configured.generate("ASSESSMENT_GENERATE", Map.of("jd_skills", List.of(Map.of("skill_name", "Java"))));
        verify(chat).call(
            org.mockito.ArgumentMatchers.<Prompt>argThat(
                prompt -> prompt.getOptions() instanceof OpenAiChatOptions options
                    && "custom-assessment".equals(options.getModel())
                    && Integer.valueOf(4000).equals(options.getMaxTokens())
            )
        );
        verify(recorder).record(argThat(row -> "custom-assessment".equals(row.get("model_name"))));
        clearInvocations(chat, recorder);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}")))));
        configured.generate("CAREER_CHAT", Map.of("message", "Hello"));
        verify(chat).call(
            org.mockito.ArgumentMatchers.<Prompt>argThat(
                prompt -> prompt.getOptions() instanceof OpenAiChatOptions options
                    && "test-chat".equals(options.getModel()) && Integer.valueOf(512).equals(options.getMaxTokens())
            )
        );
        verify(recorder).record(argThat(row -> "test-chat".equals(row.get("model_name"))));
    }

    @Test
    void assessmentModelTimeoutIsReportedWithoutLeakingProviderTextOrLosingTheReservation() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("ASSESSMENT_GENERATE"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenThrow(
            new org.springframework.web.client.ResourceAccessException(
                "sensitive provider body",
                new java.net.SocketTimeoutException("sensitive provider connection")
            )
        );
        ApiException failure = assertThrows(
            ApiException.class,
            () -> service(true)
                .generate("ASSESSMENT_GENERATE", Map.of("jd_skills", List.of(Map.of("skill_name", "Java"))))
        );
        assertEquals(504, failure.status());
        assertTrue(failure.getMessage().contains("not automatically retried"));
        assertFalse(failure.getMessage().contains("sensitive"));
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).cancel(reservation);
        verify(recorder).record(argThat(row -> "AI model request timed out".equals(row.get("error_message"))));
    }

    @Test
    void incompleteGenerationReportsTheTokenLimitAndDoesNotCallTheModelAgain() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("{\"answer\":\"Incomplete thought"),
                        ChatGenerationMetadata.builder().finishReason("length").build()
                    )
                ),
                ChatResponseMetadata.builder().usage(new DefaultUsage(80, 512)).build()
            )
        );

        ApiException failure =
            assertThrows(ApiException.class, () -> service(true).generate("CAREER_CHAT", Map.of("message", "Hello")));

        assertEquals(502, failure.status());
        assertTrue(failure.getMessage().contains("output token limit"));
        assertTrue(failure.getMessage().contains("not automatically retried"));
        verify(chat, times(1)).call(any(Prompt.class));
        verify(entitlement).complete(reservation, 592);
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(
            argThat(
                row -> Boolean.FALSE.equals(row.get("success"))
                    && "AI model output reached its token limit before finishing".equals(row.get("error_message"))
                    && Integer.valueOf(592).equals(row.get("total_tokens"))
            )
        );
    }

    @Test
    void applicationWritingRequiresBothPersistedDraftsAndInstructsConciseFactualWriting() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"subject\":\"Backend application\",\"email\":\"Dear hiring team, Java experience.\","
                                + "\"cover_letter\":\"I am applying for the supplied backend role.\"}"
                        )
                    )
                )
            )
        );

        Map<String, Object> generated = service(true).generate(
            "APPLICATION_EMAIL",
            Map.of("language", "en", "resume_text", "Built Java APIs", "job", Map.of("job_title", "Backend"))
        );

        assertEquals("Backend application", generated.get("subject"));
        verify(chat).call(org.mockito.ArgumentMatchers.<Prompt>argThat(prompt -> {
            if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
                return false;
            String system = prompt.getInstructions().getFirst().getText();
            return options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
                && system.contains("subject") && system.contains("email") && system.contains("cover_letter")
                && system.contains("email of at most 120 words") && system.contains("at most 200 words")
                && system.contains("Do not invent a recipient name") && system.contains("Respond in English.");
        }));

        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"subject\":\"Backend\",\"email\":\"Only email\"}")))
            )
        );
        assertThrows(ApiException.class, () -> service(true).generate("APPLICATION_EMAIL", Map.of()));
    }

    @Test
    void blankAssessmentModelFallsBackToTheCurrentGeneralModel() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(db.optional(contains("FROM ai_configurations"), eq("gemini")))
            .thenReturn(Optional.of(Map.of("model_name", "admin-general-model")));
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[]}")))));
        var result = service(true, "   ")
            .generate("ASSESSMENT_GENERATE", Map.of("jd_skills", List.of(Map.of("skill_name", "Java"))));
        assertEquals("admin-general-model", result.get("_model"));
        verify(chat).call(
            org.mockito.ArgumentMatchers
                .<Prompt>argThat(prompt -> "admin-general-model".equals(prompt.getOptions().getModel()))
        );
        verify(recorder).record(argThat(row -> "admin-general-model".equals(row.get("model_name"))));
    }

    @Test
    void focusedLearningTransmitsOnlyTheMissingSkillAndKeepsOtherChatBudgetsSeparate() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"title\":\"Practice Docker\",\"summary\":\"Focus on the missing skill\","
                                + "\"nodes\":[{\"skill_name\":\"Docker\",\"description\":\"Build an image\","
                                + "\"estimated_hours\":3,\"prerequisites\":[]}]}"
                        )
                    )
                )
            )
        );

        service(true).generate(
            "ROADMAP_GENERATE",
            Map.of(
                "profile_skills",
                List.of("Java"),
                "allowed_missing_skills",
                List.of(Map.of("skill_name", "Docker", "gap_status", "MISSING_EVIDENCE"))
            )
        );

        verify(chat).call(org.mockito.ArgumentMatchers.<Prompt>argThat(prompt -> {
            if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
                return false;
            return "test-chat".equals(options.getModel()) && Integer.valueOf(4000).equals(options.getMaxTokens())
                && options.getResponseFormat().getType() == OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT
                && prompt.getInstructions().getLast().getText().contains("allowed_missing_skills")
                && prompt.getInstructions().getLast().getText().contains("Docker")
                && prompt.getInstructions().getFirst().getText().contains("not a complete career roadmap");
        }));
    }

    @Test
    void embeddingWorksIndependentlyOfChatAndRejectsIncompatibleVectorsAfterAccounting() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(new float[128], 0)),
                new EmbeddingResponseMetadata("test-embedding", new DefaultUsage(4, 0))
            )
        );
        assertFalse(service(true).enabled());
        assertTrue(service(true).embeddingsEnabled());
        assertThrows(ApiException.class, () -> service(true).embed("Java"));
        verify(entitlement).complete(reservation, 4);
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(
            argThat(row -> "EMBEDDING".equals(row.get("feature_code")) && Boolean.FALSE.equals(row.get("success")))
        );
    }

    @Test
    void completeValidatedJsonAtTheTokenLimitRemainsUsable() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage("{\"answer\":\"Complete answer\"}"),
                        ChatGenerationMetadata.builder().finishReason("length").build()
                    )
                )
            )
        );

        assertEquals(
            "Complete answer",
            service(true).generate("CAREER_CHAT", Map.of("message", "Hello")).get("answer")
        );
        verify(chat, times(1)).call(any(Prompt.class));
        verify(recorder).record(argThat(row -> Boolean.TRUE.equals(row.get("success"))));
    }

    @Test
    void aValidEmbeddingWithoutReportedUsageKeepsTheBoundedReservationCharged() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class)))
            .thenReturn(new EmbeddingResponse(List.of(new Embedding(validEmbeddingVector(), 0))));
        assertEquals(1024, service(true).embed("Java").length);
        verify(entitlement).complete(reservation, 132);
        verify(embedding).call(
            argThat(
                request -> request.getInstructions().equals(List.of("Java"))
                    && request.getOptions() instanceof OpenAiEmbeddingOptions options
                    && "test-embedding".equals(options.getModel())
                    && Integer.valueOf(1024).equals(options.getDimensions())
                    && Integer.valueOf(0).equals(options.getMaxRetries())
            )
        );
    }

    @Test
    void backgroundEmbeddingAttributesQuotaAndTelemetryWithoutARequestSecurityContext() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
            new EmbeddingResponse(
                List.of(new Embedding(validEmbeddingVector(), 0)),
                new EmbeddingResponseMetadata("test-embedding", new DefaultUsage(4, 0))
            )
        );

        assertEquals(1024, service(true).embedForUser(actor, "Java").length);

        verify(entitlement).complete(reservation, 4);
        verify(recorder).record(
            argThat(
                row -> actor.equals(row.get("user_id")) && "EMBEDDING".equals(row.get("feature_code"))
                    && Boolean.TRUE.equals(row.get("success"))
            )
        );
        verifyNoInteractions(user);
    }

    @Test
    void backgroundEmbeddingFailureStillBelongsToTheExplicitActor() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("Unavailable"));

        assertThrows(ApiException.class, () -> service(true).embedForUser(actor, "Java"));

        verify(entitlement).cancel(reservation);
        verify(recorder)
            .record(argThat(row -> actor.equals(row.get("user_id")) && Boolean.FALSE.equals(row.get("success"))));
        verifyNoInteractions(user);
    }

    @Test
    void backgroundGenerationAttributesQuotaAndTelemetryWithoutARequestSecurityContext() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenThrow(new ApiException(401, "No request security context"));
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(20, 6)).build()
            )
        );

        var result = service(true).generateForUser(actor, "CAREER_CHAT", Map.of("message", "Hello"));

        assertEquals("Hello", result.get("answer"));
        verify(entitlement).complete(reservation, 26);
        verify(recorder).record(
            argThat(
                row -> actor.equals(row.get("user_id")) && row.get("guest_session_id") == null
                    && "CAREER_CHAT".equals(row.get("feature_code"))
                    && Integer.valueOf(26).equals(row.get("total_tokens")) && Boolean.TRUE.equals(row.get("success"))
            )
        );
        verifyNoInteractions(user, guests);
    }

    @Test
    void invalidBackgroundGenerationChargesAndLogsTheExplicitActor() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenThrow(new ApiException(401, "No request security context"));
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("invalid-json"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(12, 5)).build()
            )
        );

        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true).generateForUser(actor, "CAREER_CHAT", Map.of("message", "Hello"))
        );

        assertEquals(502, error.status());
        verify(entitlement).complete(reservation, 17);
        verify(entitlement, never()).cancel(any());
        verify(recorder).record(
            argThat(
                row -> actor.equals(row.get("user_id")) && row.get("guest_session_id") == null
                    && Integer.valueOf(17).equals(row.get("total_tokens")) && Boolean.FALSE.equals(row.get("success"))
            )
        );
        verifyNoInteractions(user, guests);
    }

    @Test
    void backgroundTransportFailureCancelsItsReservationAndLogsTheExplicitActor() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenThrow(new ApiException(401, "No request security context"));
        when(entitlement.reserve(eq(actor), eq("CAREER_CHAT"), anyInt())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("Unavailable"));

        assertThrows(
            ApiException.class,
            () -> service(true).generateForUser(actor, "CAREER_CHAT", Map.of("message", "Hello"))
        );

        verify(entitlement).cancel(reservation);
        verify(entitlement, never()).complete(any(), anyInt());
        verify(recorder).record(
            argThat(
                row -> actor.equals(row.get("user_id")) && "CAREER_CHAT".equals(row.get("feature_code"))
                    && Boolean.FALSE.equals(row.get("success"))
            )
        );
        verifyNoInteractions(user, guests);
    }

    @Test
    void backgroundGenerationRejectsAMissingOwnerBeforeCallingTheModelOrSpendingQuota() {
        ApiException error = assertThrows(
            ApiException.class,
            () -> service(true).generateForUser(null, "CAREER_CHAT", Map.of("message", "Hello"))
        );

        assertEquals(400, error.status());
        verifyNoInteractions(user, entitlement, guests, recorder, chats);
    }

    @Test
    void successfulGuestGenerationKeepsGuestQuotaAndTelemetryWithoutAUserOwner() {
        UUID guest = UUID.randomUUID(), reservation = UUID.randomUUID();
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(user.id()).thenThrow(new ApiException(401, "No request security context"));
        when(guests.reserve(eq(guest), eq("CAREER_CHAT"), anyInt(), isNull())).thenReturn(reservation);
        when(chat.call(any(Prompt.class))).thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(12, 5)).build()
            )
        );

        assertEquals(
            "Hello",
            service(true).generateForGuest("CAREER_CHAT", Map.of("message", "Hello"), guest).get("answer")
        );

        verify(guests).settle(reservation, 17, true);
        verify(recorder).record(
            argThat(
                row -> guest.equals(row.get("guest_session_id")) && row.get("user_id") == null
                    && Integer.valueOf(17).equals(row.get("total_tokens")) && Boolean.TRUE.equals(row.get("success"))
            )
        );
        verifyNoInteractions(user, entitlement);
    }
}
