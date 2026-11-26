package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerAssistantOwnershipTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser current = new CurrentUser();

    private final GuestAccess guests = mock(GuestAccess.class);

    private final AiService ai = mock(AiService.class);

    private final CareerAssistantService service =
        new CareerAssistantService(new CareerAssistantRepository(db), current, guests, ai, mock(ResumeService.class));

    private final UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anotherUsersConversationCannotBeReadOrSentTo() {
        when(db.one("SELECT * FROM career_assistant_conversations WHERE id=?", conversation))
            .thenReturn(Map.of("id", conversation, "user_id", UUID.randomUUID(), "status", "ACTIVE"));
        assertThrows(ApiException.class, () -> service.get(conversation));
        assertThrows(ApiException.class, () -> service.send(conversation, Map.of("message", "hello")));
        verifyNoInteractions(ai);
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void anotherGuestsSessionCannotReadOrAppendToATrialConversation() {
        UUID guest = UUID.randomUUID();
        when(guests.id("session")).thenReturn(guest);
        when(db.one("SELECT * FROM career_assistant_conversations WHERE id=?", conversation))
            .thenReturn(Map.of("id", conversation, "guest_session_id", UUID.randomUUID(), "status", "ACTIVE"));
        assertThrows(ApiException.class, () -> service.getGuest(conversation, "session"));
        assertThrows(ApiException.class, () -> service.sendGuest(conversation, Map.of("message", "hello"), "session"));
        verifyNoInteractions(ai);
    }

    @Test
    void aForeignGapAnalysisCannotBeAttachedToAConversation() {
        UUID gap = UUID.randomUUID();
        when(db.one("SELECT * FROM gap_analyses WHERE id=?", gap))
            .thenReturn(Map.of("id", gap, "user_id", UUID.randomUUID()));
        assertThrows(
            ApiException.class,
            () -> service.create(Map.of("title", "Question", "related_gap_analysis_id", gap))
        );
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void applicationWritingForwardsTheRequestedLanguageWithoutAddingPromptText() {
        when(db.one("SELECT * FROM career_assistant_conversations WHERE id=?", conversation)).thenReturn(
            Map.of("id", conversation, "user_id", actor, "status", "ACTIVE", "context_type", "application")
        );
        when(ai.generate(eq("CAREER_CHAT"), anyMap())).thenThrow(new ApiException(502, "Synthetic provider failure"));

        assertThrows(
            ApiException.class,
            () -> service.send(conversation, Map.of("message", "Write my application letter", "language", "EN"))
        );

        verify(ai).generate(
            eq("CAREER_CHAT"),
            argThat(
                input -> "en".equals(input.get("language"))
                    && "Write my application letter".equals(input.get("message")) && !input.containsKey("writing_rule")
            )
        );
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void anInvalidAssistantLanguageIsRejectedBeforeCallingAi() {
        when(db.one("SELECT * FROM career_assistant_conversations WHERE id=?", conversation))
            .thenReturn(Map.of("id", conversation, "user_id", actor, "status", "ACTIVE"));

        assertThrows(
            ApiException.class,
            () -> service.send(conversation, Map.of("message", "Write my application letter", "language", "xx"))
        );
        verifyNoInteractions(ai);
    }

    @Test
    void fractionalFeedbackRatingsCannotBeRoundedIntoValidVotes() {
        UUID reply = UUID.randomUUID();
        when(db.one(contains("FROM career_assistant_messages m"), eq(reply))).thenReturn(Map.of("user_id", actor));
        assertThrows(ApiException.class, () -> service.feedback(reply, Map.of("rating", 3.5)));
        verify(db, never()).insert(anyString(), anyMap());
    }
}
