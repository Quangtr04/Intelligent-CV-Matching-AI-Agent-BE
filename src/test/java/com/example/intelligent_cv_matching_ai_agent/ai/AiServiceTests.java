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
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiServiceTests {

    private final ObjectProvider<ChatModel> chats = mock(ObjectProvider.class);

    private final ObjectProvider<EmbeddingModel> embeddings = mock(ObjectProvider.class);

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final EntitlementService entitlement = mock(EntitlementService.class);

    private final GuestAccess guests = mock(GuestAccess.class);

    private final AiUsageRecorder recorder = mock(AiUsageRecorder.class);

    private AiService service(
        boolean enabled) {
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
            "openai",
            "test-chat",
            "test-embedding",
            512
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
        when(db.optional(contains("FROM ai_configurations"), eq("openai")))
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
            org.mockito.ArgumentMatchers
                .<Prompt>argThat(prompt -> "updated-model".equals(prompt.getOptions().getModel()))
        );
        verify(entitlement).complete(reservation, 26);
    }

    @Test
    void embeddingWorksIndependentlyOfChatAndRejectsIncompatibleVectorsAfterAccounting() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.embedForResponse(List.of("Java"))).thenReturn(
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
    void aValidEmbeddingWithoutReportedUsageKeepsTheBoundedReservationCharged() {
        UUID actor = UUID.randomUUID(), reservation = UUID.randomUUID();
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(embeddings.getIfAvailable()).thenReturn(embedding);
        when(embeddings.getObject()).thenReturn(embedding);
        when(user.id()).thenReturn(actor);
        when(entitlement.reserve(eq(actor), eq("EMBEDDING"), anyInt())).thenReturn(reservation);
        when(embedding.embedForResponse(List.of("Java")))
            .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[1536], 0))));
        assertEquals(1536, service(true).embed("Java").length);
        verify(entitlement).complete(reservation, 132);
    }
}
