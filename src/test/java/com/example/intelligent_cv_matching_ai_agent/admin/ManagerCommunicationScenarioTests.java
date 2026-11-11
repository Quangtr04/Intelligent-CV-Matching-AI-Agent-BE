package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement.AnnouncementRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement.AnnouncementTargetRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.support.SupportTicketUpdateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.support.SupportMessageRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement.AnnouncementRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.support.SupportRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.announcement.AnnouncementService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.support.SupportService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerCommunicationScenarioTests {

    private final UUID actor = UUID.randomUUID();

    private final UUID record = UUID.randomUUID();

    private final UUID candidate = UUID.randomUUID();

    private final AnnouncementRepository announcementDb = mock(AnnouncementRepository.class);

    private final SupportRepository supportDb = mock(SupportRepository.class);

    private final AuditService audit = mock(AuditService.class);

    private final CurrentUser current = new CurrentUser();

    private final AnnouncementService announcements = new AnnouncementService(announcementDb, current, audit);

    private final SupportService support = new SupportService(supportDb, current, audit);

    @BeforeEach
    void managerIdentity() {
        login("MANAGER");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void login(
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))
        );
    }

    @Test
    void managerCreatesATargetedDraftWithoutSendingNotificationsUntilPublication() {
        when(announcementDb.insertAnnouncements(anyMap())).thenReturn(record);
        when(announcementDb.getAnnouncementsById(record)).thenReturn(Map.of("id", record, "status", "DRAFT"));
        var request = new AnnouncementRequest(
            "Service availability",
            "Consultation slots are available.",
            null,
            List.of(new AnnouncementTargetRequest("ROLE", "CANDIDATE", null))
        );

        assertEquals("DRAFT", announcements.create(request).get("status"));

        verify(announcementDb).insertAnnouncements(
            argThat(fields -> actor.equals(fields.get("created_by_user_id")) && "DRAFT".equals(fields.get("status")))
        );
        verify(announcementDb).insertAnnouncementTargets(
            argThat(
                fields -> record.equals(fields.get("announcement_id")) && "ROLE".equals(fields.get("target_type"))
                    && "CANDIDATE".equals(fields.get("target_role"))
            )
        );
        verify(announcementDb, never()).updateNotificationsById(any());
        verify(audit).record(eq("CREATE_ANNOUNCEMENT"), eq("ANNOUNCEMENT"), eq(record), isNull(), any());
    }

    @Test
    void draftWithoutAnAudienceCannotPublishOrDispatchNotifications() {
        when(announcementDb.getAnnouncementsByIdForUpdate2(record)).thenReturn(Map.of("status", "DRAFT"));
        when(announcementDb.countAnnouncementTargetsByAnnouncementId(record)).thenReturn(0L);

        assertEquals(400, assertThrows(ApiException.class, () -> announcements.publish(record)).status());

        verify(announcementDb, never()).updateAnnouncementsById2(any());
        verify(announcementDb, never()).updateNotificationsById(any());
        verifyNoInteractions(audit);
    }

    @Test
    void repeatedPublicationCannotDispatchTheSameAnnouncementTwice() {
        when(announcementDb.getAnnouncementsByIdForUpdate2(record))
            .thenReturn(Map.of("status", "DRAFT"), Map.of("status", "PUBLISHED"));
        when(announcementDb.countAnnouncementTargetsByAnnouncementId(record)).thenReturn(1L);
        when(announcementDb.getAnnouncementsById(record)).thenReturn(Map.of("id", record, "status", "PUBLISHED"));

        assertEquals("PUBLISHED", announcements.publish(record).get("status"));
        assertEquals(409, assertThrows(ApiException.class, () -> announcements.publish(record)).status());

        verify(announcementDb, times(1)).updateAnnouncementsById2(record);
        verify(announcementDb, times(1)).updateNotificationsById(record);
        verify(audit, times(1)).record(eq("PUBLISH_ANNOUNCEMENT"), eq("ANNOUNCEMENT"), eq(record), any(), any());
    }

    @Test
    void publishedAnnouncementCannotBeEditedOrHaveItsAudienceReplaced() {
        when(announcementDb.getAnnouncementsByIdForUpdate(record)).thenReturn(Map.of("status", "PUBLISHED"));
        var request = new AnnouncementRequest(
            "Changed",
            "Changed content",
            null,
            List.of(new AnnouncementTargetRequest("ALL", null, null))
        );

        assertEquals(409, assertThrows(ApiException.class, () -> announcements.edit(record, request)).status());

        verify(announcementDb, never()).updateAnnouncementsById(any(), any(), any(), any());
        verify(announcementDb, never()).deleteAnnouncementTargetsByAnnouncementId(any());
        verifyNoInteractions(audit);
    }

    @Test
    void readingAForeignNotificationCannotMarkItRead() {
        when(announcementDb.getNotificationsById(record, actor))
            .thenThrow(ApiException.notFound("Notification not found"));

        assertEquals(404, assertThrows(ApiException.class, () -> announcements.read(record)).status());

        verify(announcementDb, never()).updateNotificationsById2(any(), any());
    }

    @Test
    void managerReplyNotifiesTheTicketOwnerAndKeepsTheManagerAsSender() {
        UUID message = UUID.randomUUID();
        when(supportDb.getTicketForUpdate(record)).thenReturn(Map.of("user_id", candidate, "status", "OPEN"));
        when(supportDb.insertSupportTicketMessages(anyMap())).thenReturn(message);
        when(supportDb.getSupportTicketMessagesById(message)).thenReturn(Map.of("id", message));

        assertEquals(message, support.message(record, new SupportMessageRequest("Please check your order.")).get("id"));

        verify(supportDb).insertSupportTicketMessages(
            argThat(fields -> actor.equals(fields.get("sender_user_id")) && record.equals(fields.get("ticket_id")))
        );
        verify(supportDb).insertNotifications(
            argThat(
                fields -> candidate.equals(fields.get("user_id")) && "SUPPORT_REPLY".equals(fields.get("type"))
                    && record.equals(fields.get("reference_id"))
            )
        );
        verify(supportDb).updateSupportTicketsById(record);
    }

    @Test
    void managerReplyOnTheirOwnTicketDoesNotSendASelfNotification() {
        when(supportDb.getTicketForUpdate(record)).thenReturn(Map.of("user_id", actor, "status", "IN_PROGRESS"));

        support.message(record, new SupportMessageRequest("Additional details."));

        verify(supportDb).insertSupportTicketMessages(anyMap());
        verify(supportDb, never()).insertNotifications(anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "RESOLVED"})
    void managerUpdatesSupportProgressUnderTheTicketLockAndRecordsTheAudit(
        String status) {
        when(supportDb.getTicketForUpdate(record)).thenReturn(Map.of("user_id", candidate, "status", "OPEN"));
        when(supportDb.getTicket(record)).thenReturn(Map.of("user_id", candidate, "status", status));

        assertEquals(
            status,
            support.update(record, new SupportTicketUpdateRequest(status, "HIGH", null)).get("status")
        );

        var order = inOrder(supportDb);
        order.verify(supportDb).getTicketForUpdate(record);
        order.verify(supportDb).updateSupportTicketsById2(status, "HIGH", null, status, record);
        order.verify(supportDb, times(2)).getTicket(record);
        verify(audit).record(
            eq("UPDATE_SUPPORT_TICKET"),
            eq("SUPPORT_TICKET"),
            eq(record),
            argThat(before -> before instanceof Map<?, ?> values && "OPEN".equals(values.get("status"))),
            argThat(after -> after instanceof Map<?, ?> values && status.equals(values.get("status")))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CAREER_CONSULTANT"})
    void nonOperatorsCannotResolveSomeoneElsesTicket(
        String role) {
        login(role);

        assertEquals(
            403,
            assertThrows(
                ApiException.class,
                () -> support.update(record, new SupportTicketUpdateRequest("RESOLVED", "NORMAL", null))
            ).status()
        );

        verifyNoInteractions(supportDb, audit);
    }
}
