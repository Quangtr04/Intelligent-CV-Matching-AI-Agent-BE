package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.ai.AiRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiUsageRecorder;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServiceTests {

    @Test
    void profileAlignmentUsesNativeSchemaBoundedContextAndTheSameQuotaAccounting() {
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
                    OllamaChatOptions options = (OllamaChatOptions) prompt.getOptions();
                    return Integer.valueOf(4000).equals(options.getNumPredict())
                        && Integer.valueOf(16384).equals(options.getNumCtx())
                        && Double.valueOf(0.0).equals(options.getTemperature()) && options.getFormat() instanceof Map;
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

    private AiService service(
        boolean enabled) {
        return service(enabled, "test-assessment", false);
    }

    private AiService service(
        boolean enabled,
        String assessmentModel,
        boolean assessmentThink) {
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
            "ollama",
            "test-chat",
            "test-embedding",
            1024,
            8192,
            512,
            4000,
            4000,
            assessmentModel,
            assessmentThink,
            16384
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
        when(db.optional(contains("FROM ai_configurations"), eq("ollama")))
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
        verify(chat)
            .call(
                org.mockito.ArgumentMatchers
                    .<Prompt>argThat(
                        prompt -> "updated-model".equals(prompt.getOptions().getModel())
                            && prompt
                                .getOptions() instanceof org.springframework.ai.ollama.api.OllamaChatOptions options
                            && options.getFormat() instanceof Map<?, ?> format
                            && List.of("answer").equals(format.get("required"))
                            && Integer.valueOf(512).equals(options.getNumPredict()) && options.getThinkOption() == null
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
                && prompt.getOptions() instanceof org.springframework.ai.ollama.api.OllamaChatOptions options
                && options.getFormat() instanceof Map<?, ?> format && format.get("required") instanceof List<?> required
                && required.contains("writing_quality") && input.contains("- Built a Java API\\n- Wrote API tests");
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
        verify(recorder).record(argThat(row -> Boolean.FALSE.equals(row.get("success"))));
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
            if (!(prompt.getOptions() instanceof org.springframework.ai.ollama.api.OllamaChatOptions options)
                || !(options.getFormat() instanceof Map<?, ?> format))
                return false;
            Map<?, ?> properties = (Map<?, ?>) format.get("properties");
            Map<?, ?> questionSchema = (Map<?, ?>) properties.get("questions");
            Map<?, ?> item = (Map<?, ?>) questionSchema.get("items");
            Map<?, ?> fields = (Map<?, ?>) item.get("properties");
            Map<?, ?> skill = (Map<?, ?>) fields.get("related_skill_name");
            return "test-assessment".equals(options.getModel())
                && Objects.equals(
                    OllamaChatOptions.builder().disableThinking().build().getThinkOption(),
                    options.getThinkOption()
                ) && Integer.valueOf(4000).equals(options.getNumPredict())
                && Integer.valueOf(16384).equals(options.getNumCtx()) && List.of("Docker").equals(skill.get("enum"))
                && new ArrayList<>(fields.keySet()).indexOf("question_text") < new ArrayList<>(fields.keySet())
                    .indexOf("jd_evidence")
                && prompt.getInstructions().getFirst().getText().contains("ONE selected job description")
                && prompt.getInstructions().getFirst().getText().contains("Use established technical knowledge")
                && prompt.getInstructions().getFirst().getText().contains("questions 6-10 have part=2");
        }));
    }

    @Test
    void assessmentThinkingAndTrimmedModelAreScopedToAssessmentOnly() {
        ChatModel chat = mock(ChatModel.class);
        when(chats.getIfAvailable()).thenReturn(chat);
        when(chats.getObject()).thenReturn(chat);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[]}")))));
        AiService configured = service(true, "  custom-assessment  ", true);
        configured.generate("ASSESSMENT_GENERATE", Map.of("jd_skills", List.of(Map.of("skill_name", "Java"))));
        verify(chat).call(
            org.mockito.ArgumentMatchers.<Prompt>argThat(
                prompt -> prompt.getOptions() instanceof OllamaChatOptions options
                    && "custom-assessment".equals(options.getModel())
                    && Integer.valueOf(16384).equals(options.getNumCtx())
                    && Objects.equals(
                        OllamaChatOptions.builder().enableThinking().build().getThinkOption(),
                        options.getThinkOption()
                    )
            )
        );
        verify(recorder).record(argThat(row -> "custom-assessment".equals(row.get("model_name"))));
        clearInvocations(chat, recorder);
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"answer\":\"Hello\"}")))));
        configured.generate("CAREER_CHAT", Map.of("message", "Hello"));
        verify(chat).call(
            org.mockito.ArgumentMatchers.<Prompt>argThat(
                prompt -> prompt.getOptions() instanceof OllamaChatOptions options
                    && "test-chat".equals(options.getModel()) && options.getThinkOption() == null
                    && options.getNumCtx() == null
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
        verify(recorder).record(argThat(row -> "Local model request timed out".equals(row.get("error_message"))));
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
        verify(recorder)
            .record(
                argThat(
                    row -> Boolean.FALSE.equals(row.get("success"))
                        && "Local model output reached its token limit before finishing"
                            .equals(row.get("error_message"))
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
            if (!(prompt.getOptions() instanceof OllamaChatOptions options)
                || !(options.getFormat() instanceof Map<?, ?> format))
                return false;
            String system = prompt.getInstructions().getFirst().getText();
            return List.of("subject", "email", "cover_letter").equals(format.get("required"))
                && Boolean.FALSE.equals(format.get("additionalProperties"))
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
        when(db.optional(contains("FROM ai_configurations"), eq("ollama")))
            .thenReturn(Optional.of(Map.of("model_name", "admin-general-model")));
        when(chat.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"questions\":[]}")))));
        var result = service(true, "   ", false)
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
            if (!(prompt.getOptions() instanceof org.springframework.ai.ollama.api.OllamaChatOptions options)
                || !(options.getFormat() instanceof Map<?, ?> format))
                return false;
            Map<?, ?> properties = (Map<?, ?>) format.get("properties");
            Map<?, ?> nodes = (Map<?, ?>) properties.get("nodes");
            Map<?, ?> item = (Map<?, ?>) ((List<?>) nodes.get("prefixItems")).getFirst();
            Map<?, ?> fields = (Map<?, ?>) item.get("properties");
            Map<?, ?> skill = (Map<?, ?>) fields.get("skill_name");
            return "test-chat".equals(options.getModel()) && options.getThinkOption() == null
                && options.getNumCtx() == null && Integer.valueOf(4000).equals(options.getNumPredict())
                && "Docker".equals(skill.get("const")) && !nodes.containsKey("items")
                && !properties.containsKey("skill_gaps")
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
            .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[1024], 0))));
        assertEquals(1024, service(true).embed("Java").length);
        verify(entitlement).complete(reservation, 132);
        verify(embedding)
            .call(
                argThat(
                    request -> request.getInstructions().equals(List.of("Java"))
                        && request
                            .getOptions() instanceof org.springframework.ai.ollama.api.OllamaEmbeddingOptions options
                        && "test-embedding".equals(options.getModel())
                        && Integer.valueOf(8192).equals(options.getNumCtx())
                        && Boolean.FALSE.equals(options.getTruncate())
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
                List.of(new Embedding(new float[1024], 0)),
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
