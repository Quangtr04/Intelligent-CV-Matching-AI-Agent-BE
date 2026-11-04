package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai.UpdateAssistantConversationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.ai.AssistantMessageRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerAssistantConversationTests {

    private final CareerAssistantRepository db = mock(CareerAssistantRepository.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final UUID actor = UUID.randomUUID();

    private final UUID conversation = UUID.randomUUID();

    private final UUID resume = UUID.randomUUID();

    private final CareerAssistantService service =
        new CareerAssistantService(db, new CurrentUser(), mock(GuestAccess.class), ai, resumes);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void partialUpdatesDistinguishDetachedContextFromUnchangedContext() {
        JsonMapper json = JsonMapper.builder().build();
        UpdateAssistantConversationRequest request = json
            .readValue("{\"title\":\"My career\",\"related_job_id\":null}", UpdateAssistantConversationRequest.class);

        assertEquals("My career", request.toInputMap().get("title"));
        assertTrue(request.toInputMap().containsKey("related_job_id"));
        assertNull(request.toInputMap().get("related_job_id"));
        assertFalse(request.toInputMap().containsKey("related_resume_id"));
        request.toInputMap().clear();
        assertEquals(2, request.toInputMap().size());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            for (String title : new String[]{"null", "\" \""}) {
                UpdateAssistantConversationRequest invalid =
                    json.readValue("{\"title\":" + title + "}", UpdateAssistantConversationRequest.class);
                assertFalse(factory.getValidator().validate(invalid).isEmpty());
            }
        }
    }

    @Test
    void requestedLanguageSurvivesDeserializationAndRejectsUnsupportedValues() {
        JsonMapper json = JsonMapper.builder().build();
        AssistantMessageRequest request =
            json.readValue("{\"message\":\"Career question\",\"language\":\"EN\"}", AssistantMessageRequest.class);
        assertEquals("EN", request.toInputMap().get("language"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            AssistantMessageRequest invalid =
                json.readValue("{\"message\":\"Career question\",\"language\":\"xx\"}", AssistantMessageRequest.class);
            assertFalse(factory.getValidator().validate(invalid).isEmpty());
        }
    }

    @Test
    void detachingAJobPreservesTheResumeAndMessages() {
        Map<String, Object> thread = thread();
        thread.put("related_resume_id", resume);
        thread.put("related_job_id", UUID.randomUUID());
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread);
        Map<String, Object> expected = new LinkedHashMap<>(thread);
        expected.put("related_job_id", null);
        expected.put("context_type", "resume");
        when(db.getCareerAssistantConversationsById(conversation)).thenReturn(expected);
        List<Map<String, Object>> messages = List.of(Map.of("message_text", "Keep this history"));
        when(db.listCareerAssistantMessagesByConversationId(conversation)).thenReturn(messages);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("related_job_id", null);

        Map<String, Object> result = service.update(conversation, patch);

        assertEquals(resume, result.get("related_resume_id"));
        assertNull(result.get("related_job_id"));
        assertEquals("resume", result.get("context_type"));
        assertEquals(messages, result.get("messages"));
        verify(db)
            .updateConversationDetails(eq("Original"), eq("resume"), eq(resume), isNull(), any(), eq(conversation));
        verifyNoInteractions(resumes, ai);
    }

    @Test
    void attachingAResumeChecksItsOwnershipBeforeChangingTheConversation() {
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread());
        when(resumes.owned(resume)).thenThrow(ApiException.notFound("Resume not found"));

        assertThrows(ApiException.class, () -> service.update(conversation, Map.of("related_resume_id", resume)));

        verify(db, never()).updateConversationDetails(any(), any(), any(), any(), any(), any());
    }

    @Test
    void foreignAndArchivedConversationsCannotBeEditedOrCleared() {
        Map<String, Object> thread = thread();
        thread.put("user_id", UUID.randomUUID());
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread);
        assertThrows(ApiException.class, () -> service.update(conversation, Map.of("title", "Renamed")));
        assertThrows(ApiException.class, () -> service.clearMessages(conversation));

        thread.put("user_id", actor);
        thread.put("status", "ARCHIVED");
        assertThrows(ApiException.class, () -> service.update(conversation, Map.of("title", "Renamed")));
        assertThrows(ApiException.class, () -> service.clearMessages(conversation));
        verify(db, never()).clearConversationMessages(any());
        verify(db, never()).updateConversationDetails(any(), any(), any(), any(), any(), any());
    }

    @Test
    void clearingAConversationDeletesDependentRowsBeforeMessagesAndResetsItsTimestamp() {
        SqlStore store = mock(SqlStore.class);
        CareerAssistantRepository repository = new CareerAssistantRepository(store);
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread());
        doAnswer(invocation -> {
            repository.clearConversationMessages(conversation);
            return null;
        }).when(db).clearConversationMessages(conversation);

        service.clearMessages(conversation);

        InOrder order = inOrder(store);
        order.verify(store).update(startsWith("DELETE FROM career_assistant_feedback"), eq(conversation));
        order.verify(store).update(startsWith("DELETE FROM career_assistant_message_sources"), eq(conversation));
        order.verify(store).update("DELETE FROM career_assistant_messages WHERE conversation_id=?", conversation);
        order.verify(store).update(contains("last_message_at=NULL"), any(Instant.class), eq(conversation));
        verifyNoMoreInteractions(store);
    }

    @Test
    void aContextChangeDuringGenerationDiscardsTheStaleAnswer() {
        Map<String, Object> original = thread();
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put("updated_at", Instant.parse("2026-01-01T00:00:01Z"));
        when(db.getCareerAssistantConversationsById(conversation)).thenReturn(original);
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(changed);
        when(db.findCandidateProfilesByUserId(actor)).thenReturn(Optional.empty());
        when(ai.generate(eq("CAREER_CHAT"), anyMap()))
            .thenReturn(Map.of("answer", "Outdated answer", "_model", "test"));

        ApiException exception =
            assertThrows(ApiException.class, () -> service.send(conversation, Map.of("message", "Check my CV")));

        assertEquals(409, exception.status());
        verify(db, never()).insertCareerAssistantMessages(anyMap());
    }

    @Test
    void changingOrRemovingFeedbackOnlyAffectsTheActorsVote() {
        UUID message = UUID.randomUUID();
        UUID vote = UUID.randomUUID();
        Map<String, Object> messageRow = Map.of("conversation_id", conversation, "user_id", actor);
        when(db.getCareerAssistantMessagesById2(message)).thenReturn(messageRow);
        when(db.insertCareerAssistantFeedback(anyMap())).thenReturn(vote);
        when(db.getCareerAssistantFeedbackById(vote)).thenReturn(Map.of("id", vote, "is_helpful", false));

        Map<String, Object> result = service.feedback(message, Map.of("is_helpful", false, "rating", 1));

        assertEquals(false, result.get("is_helpful"));
        InOrder order = inOrder(db);
        order.verify(db).getCareerAssistantMessagesById2(message);
        order.verify(db).getCareerAssistantConversationsByIdForUpdate(conversation);
        order.verify(db).getCareerAssistantMessagesById2(message);
        order.verify(db).deleteMessageFeedback(message, actor);
        order.verify(db).insertCareerAssistantFeedback(argThat(fields -> actor.equals(fields.get("user_id"))));

        service.clearFeedback(message);
        verify(db, times(2)).deleteMessageFeedback(message, actor);
    }

    private Map<String, Object> thread() {
        return new LinkedHashMap<>(
            Map.of(
                "id",
                conversation,
                "user_id",
                actor,
                "status",
                "ACTIVE",
                "title",
                "Original",
                "context_type",
                "general",
                "updated_at",
                Instant.parse("2026-01-01T00:00:00Z")
            )
        );
    }
}
