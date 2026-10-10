package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.support.SupportRepository;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement.AnnouncementRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.support.SupportService;

import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.announcement.AnnouncementService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.support.SupportMessageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportAccessTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID login(
        String role) {
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))
        );
        return user;
    }

    @Test
    void anotherCandidateCannotReadTicketMessages() {
        login("CANDIDATE");
        SqlStore db = mock(SqlStore.class);
        UUID ticket = UUID.randomUUID();
        when(db.one(anyString(), eq(ticket))).thenReturn(Map.of("user_id", UUID.randomUUID(), "status", "OPEN"));
        var service = new SupportService(new SupportRepository(db), new CurrentUser(), mock(AuditService.class));
        assertEquals(404, assertThrows(ApiException.class, () -> service.detail(ticket)).status());
        verify(db, never()).list(anyString(), any(Object[].class));
    }

    @Test
    void closedTicketRejectsNewMessagesWhileHoldingTicketLock() {
        UUID user = login("CANDIDATE");
        SqlStore db = mock(SqlStore.class);
        UUID ticket = UUID.randomUUID();
        when(db.one(anyString(), eq(ticket))).thenReturn(Map.of("user_id", user, "status", "CLOSED"));
        var service = new SupportService(new SupportRepository(db), new CurrentUser(), mock(AuditService.class));
        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.message(ticket, new SupportMessageRequest("Please reply")))
                .status()
        );
        verify(db).one(contains("FOR UPDATE"), eq(ticket));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void candidateCannotPublishAdminAnnouncement() {
        login("CANDIDATE");
        SqlStore db = mock(SqlStore.class);
        assertEquals(
            403,
            assertThrows(
                ApiException.class,
                () -> new AnnouncementService(
                    new AnnouncementRepository(db),
                    new CurrentUser(),
                    mock(AuditService.class)
                ).publish(UUID.randomUUID())
            ).status()
        );
        verifyNoInteractions(db);
    }

    @Test
    void expiredDraftCannotSendPublicationNotifications() {
        login("ADMIN");
        SqlStore db = mock(SqlStore.class);
        UUID announcement = UUID.randomUUID();
        when(db.one(anyString(), eq(announcement))).thenReturn(Map.of("status", "DRAFT", "is_expired", true));
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> new AnnouncementService(
                    new AnnouncementRepository(db),
                    new CurrentUser(),
                    mock(AuditService.class)
                ).publish(announcement)
            ).status()
        );
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
