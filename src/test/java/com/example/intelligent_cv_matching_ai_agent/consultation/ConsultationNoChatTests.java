package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.consultation.ConsultationMessageRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationNoChatTests {

    private final ConsultationRepository db = mock(ConsultationRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ConsultationService service = new ConsultationService(db, user);

    private final UUID threadId = UUID.randomUUID();

    private final UUID candidateId = UUID.randomUUID();

    private final UUID consultantId = UUID.randomUUID();

    private final UUID messageId = UUID.randomUUID();

    private Map<String, Object> thread;

    @BeforeEach
    void setup() {
        thread = new LinkedHashMap<>();
        thread.put("id", threadId);
        thread.put("candidate_user_id", candidateId);
        thread.put("consultant_user_id", consultantId);
        thread.put("request_type", "SESSION_BOOKING");
        thread.put("status", "OPEN");
        thread.put("subject", "Scheduled Google Meet consultation");
        when(user.id()).thenReturn(candidateId);
        when(db.getConsultationThreadsById(threadId)).thenReturn(thread);
    }

    @Test
    void scheduledBookingMessageHistoryIsUnavailableToEitherParticipantAndAdmin() {
        for (UUID actor : List.of(candidateId, consultantId, UUID.randomUUID())) {
            when(user.id()).thenReturn(actor);
            when(user.hasRole("ADMIN")).thenReturn(!actor.equals(candidateId) && !actor.equals(consultantId));
            assertThrows(ApiException.class, () -> service.messages(threadId, 20, 0));
        }
        thread.put("status", "CLOSED");
        assertThrows(ApiException.class, () -> service.messages(threadId, 20, 0));
        verify(db, never()).listConsultationMessagesByThreadId(any(), any(), any());
    }

    @Test
    void acceptedBookingCannotSendChatEvenDuringItsScheduledTime() {
        when(db.bookingForThread(threadId)).thenReturn(
            Map.of(
                "status",
                "ACCEPTED",
                "starts_at",
                OffsetDateTime.now().minusMinutes(5),
                "ends_at",
                OffsetDateTime.now().plusMinutes(55)
            )
        );
        assertThrows(
            ApiException.class,
            () -> service.send(threadId, new ConsultationMessageRequest("Use the scheduled Meet instead"))
        );
        verify(db, never()).insertConsultationMessages(anyMap());
        verify(db, never()).insertNotifications(anyMap());
    }

    @Test
    void scheduledBookingCannotAcknowledgeMessageReads() {
        assertThrows(ApiException.class, () -> service.read(threadId, messageId));
        verify(db, never()).getConsultationMessagesById2(any(), any());
        verify(db, never()).updateConsultationMessagesById(any(), any());
    }

    @Test
    void scheduledBookingCannotOpenChatEventStream() {
        assertThrows(ApiException.class, () -> service.events(threadId));
        verify(db, never()).listConsultationMessagesByThreadId(any(), any(), any());
    }

    @Test
    void scheduledBookingStillExposesOwnedReadOnlyThreadAndCandidateContext() {
        thread.put("status", "CLOSED");
        Map<String, Object> candidate = Map.of("id", candidateId, "full_name", "Synthetic Candidate");
        Map<String, Object> resume = Map.of("id", UUID.randomUUID(), "title", "Reviewed CV");
        thread.put("resume_id", resume.get("id"));
        when(db.findCandidateProfilesByUserId(candidateId)).thenReturn(Optional.of(new LinkedHashMap<>()));
        when(db.listCandidateProfileSkillsByUserId(candidateId)).thenReturn(List.of());
        when(db.getUsersById(candidateId)).thenReturn(candidate);
        when(db.getResumesById(resume.get("id"), candidateId)).thenReturn(resume);
        assertEquals(thread, service.thread(threadId));
        Map<String, Object> context = service.candidateProfile(threadId, null);
        assertEquals(candidate, context.get("candidate"));
        assertEquals(resume, context.get("resume"));
        verify(db, never()).findResumesByUserId(any());
        verify(db, never()).insertConsultationMessages(anyMap());
    }

    @Test
    void unrelatedUserCannotReadScheduledBookingContext() {
        when(user.id()).thenReturn(UUID.randomUUID());
        assertEquals(404, assertThrows(ApiException.class, () -> service.thread(threadId)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.candidateProfile(threadId, null)).status());
        verify(db, never()).getUsersById(any());
    }

    @Test
    void legacyThreadMessageHistoryRemainsAvailable() {
        thread.put("request_type", "CV_REVIEW");
        Map<String, Object> message = Map.of("id", messageId, "content", "Historical advice");
        when(db.listConsultationMessagesByThreadId(threadId, 20, 0)).thenReturn(List.of(message));
        assertEquals(List.of(message), service.messages(threadId, 20, 0));
    }

    @Test
    void legacyThreadCanStillSendMessages() {
        thread.put("request_type", "CV_REVIEW");
        Map<String, Object> message = Map.of("id", messageId, "content", "Historical advice");
        when(db.insertConsultationMessages(anyMap())).thenReturn(messageId);
        when(db.getConsultationMessagesById(messageId)).thenReturn(message);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(message, service.send(threadId, new ConsultationMessageRequest("Historical advice")));
            verify(db).insertConsultationMessages(
                argThat(
                    values -> threadId.equals(values.get("thread_id"))
                        && candidateId.equals(values.get("sender_user_id"))
                        && "Historical advice".equals(values.get("content"))
                )
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void legacyThreadReadAcknowledgementRemainsAvailable() {
        thread.put("request_type", "CV_REVIEW");
        assertEquals(Map.of("id", messageId, "read", true), service.read(threadId, messageId));
        verify(db).getConsultationMessagesById2(messageId, threadId);
        verify(db).updateConsultationMessagesById(messageId, candidateId);
    }

    @Test
    void legacyThreadCanStillSubscribeToChatEvents() {
        thread.put("request_type", "CV_REVIEW");
        var emitter = service.events(threadId);
        assertNotNull(emitter);
        emitter.complete();
    }
}
