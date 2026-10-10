package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;

import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;

import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountTest {

    @Test
    void suspensionCannotBeUsedToBypassPendingEmailVerification() {
        SqlStore db = mock(SqlStore.class);
        UUID id = UUID.randomUUID();
        when(db.one(contains("FROM users"), eq(id)))
            .thenReturn(Map.of("id", id, "status", "SUSPENDED", "role", "CANDIDATE"));
        var service = new AdminService(
            new AdminRepository(db),
            mock(CurrentUser.class),
            mock(AuditService.class),
            mock(TokenService.class),
            mock(PasswordEncoder.class)
        );
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.edit(id, new AccountUpdateRequest("Person", "CANDIDATE", "ACTIVE"))
            ).status()
        );
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
