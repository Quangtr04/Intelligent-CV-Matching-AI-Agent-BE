package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerAssistantCourseSourcesTests {

    private final CareerAssistantRepository db = mock(CareerAssistantRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final GuestAccess guests = mock(GuestAccess.class);

    private final AiService ai = mock(AiService.class);

    private final CareerAssistantService service =
        new CareerAssistantService(db, current, guests, ai, mock(ResumeService.class));

    private final UUID actor = UUID.randomUUID();

    private final UUID conversation = UUID.randomUUID();

    private final UUID reply = UUID.randomUUID();

    @BeforeEach
    void configureSuccessfulConversation() {
        Map<String, Object> thread = Map.of("id", conversation, "user_id", actor, "status", "ACTIVE");
        when(current.id()).thenReturn(actor);
        when(db.getCareerAssistantConversationsById(conversation)).thenReturn(thread);
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread);
        when(db.findCandidateProfilesByUserId(actor)).thenReturn(Optional.empty());
        when(db.insertCareerAssistantMessages(anyMap())).thenReturn(UUID.randomUUID(), reply);
        when(db.getCareerAssistantMessagesById(reply)).thenReturn(Map.of("id", reply, "message_text", "Learn Java"));
        when(ai.generate(eq("CAREER_CHAT"), anyMap())).thenReturn(answer());
    }

    @Test
    void approvedCourseUrlsReachTheAiAndAreSnapshottedWithTheReply() {
        Map<String, Object> source = source();
        source.put("source_url", "https://www.coursera.org/learn/java-programming");
        source.put("source_provider", "Coursera");
        List<Map<String, Object>> sources = List.of(source);
        when(ai.embed("Recommend a Java course")).thenReturn(new float[]{1, 0});
        when(ai.provider()).thenReturn("OPENAI");
        when(ai.embeddingModel()).thenReturn("test-embedding");
        when(db.listRagChunksByProvider(any(), any(), any(), any(), any(), any())).thenReturn(sources);
        Map<String, Object> storedCitation = new LinkedHashMap<>();
        when(db.insertCareerAssistantMessageSources(anyMap())).thenAnswer(invocation -> {
            storedCitation.putAll(invocation.getArgument(0));
            return UUID.randomUUID();
        });
        when(db.listCareerAssistantMessageSourcesByMessageId(reply)).thenAnswer(invocation -> List.of(storedCitation));

        Map<String, Object> response =
            service.send(conversation, Map.of("message", "Recommend a Java course", "retrieve", true));

        verify(ai).generate(eq("CAREER_CHAT"), argThat(input -> sources.equals(input.get("sources"))));
        assertEquals(reply, storedCitation.get("message_id"));
        assertEquals(source.get("document_id"), storedCitation.get("rag_document_id"));
        assertEquals(source.get("chunk_id"), storedCitation.get("rag_chunk_id"));
        assertEquals("https://www.coursera.org/learn/java-programming", storedCitation.get("source_url"));
        assertEquals("Coursera", storedCitation.get("source_provider"));
        source.put("source_url", "https://www.coursera.org/learn/another-course");
        assertEquals("https://www.coursera.org/learn/java-programming", storedCitation.get("source_url"));
        assertEquals(List.of(storedCitation), response.get("sources"));

        when(db.listCareerAssistantMessageSourcesByConversationId(conversation)).thenReturn(List.of(storedCitation));
        assertEquals(List.of(storedCitation), service.get(conversation).get("sources"));
    }

    @Test
    void genericRagSourcesWithoutCourseUrlsContinueToSaveSuccessfully() {
        Map<String, Object> source = source();
        source.put("source_url", null);
        source.put("source_provider", null);
        when(ai.embed("Help me prepare")).thenReturn(new float[]{1, 0});
        when(db.listRagChunksByProvider(any(), any(), any(), any(), any(), any())).thenReturn(List.of(source));

        assertDoesNotThrow(() -> service.send(conversation, Map.of("message", "Help me prepare", "retrieve", true)));

        verify(db).insertCareerAssistantMessageSources(
            argThat(
                citation -> citation.containsKey("source_url") && citation.get("source_url") == null
                    && citation.get("source_provider") == null
                    && source.get("title").equals(citation.get("source_label"))
            )
        );
    }

    @Test
    void guestChatDoesNotRetrieveOrPersistCuratedCourseSources() {
        UUID guest = UUID.randomUUID();
        Map<String, Object> thread = Map.of("id", conversation, "guest_session_id", guest, "status", "ACTIVE");
        when(guests.id("guest-session")).thenReturn(guest);
        when(db.getCareerAssistantConversationsById(conversation)).thenReturn(thread);
        when(db.getCareerAssistantConversationsByIdForUpdate(conversation)).thenReturn(thread);
        when(ai.generateForGuest(eq("CAREER_CHAT"), anyMap(), eq(guest))).thenReturn(answer());

        service.sendGuest(conversation, Map.of("message", "Recommend a course", "retrieve", true), "guest-session");

        verify(ai, never()).embed(anyString());
        verify(db, never()).listRagChunksByProvider(any(), any(), any(), any(), any(), any());
        verify(db, never()).insertCareerAssistantMessageSources(anyMap());
        verify(ai)
            .generateForGuest(eq("CAREER_CHAT"), argThat(input -> List.of().equals(input.get("sources"))), eq(guest));
    }

    private Map<String, Object> source() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("chunk_id", UUID.randomUUID());
        source.put("document_id", UUID.randomUUID());
        source.put("title", "Java programming");
        source.put("content", "Java fundamentals and exercises");
        source.put("similarity", 0.9);
        return source;
    }

    private Map<String, Object> answer() {
        return Map.of("answer", "Learn Java", "_model", "test-model");
    }
}
