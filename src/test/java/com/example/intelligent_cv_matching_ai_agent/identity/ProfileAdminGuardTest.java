package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ProfileRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.jpa.UserJpaRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.ProfileService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeProfileAlignmentRefreshService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileAdminGuardTest {

    @Test
    void selfDeletionProtectsCurrentDatabaseRoleEvenWhenBearerRoleWasOlder() {
        SqlStore db = mock(SqlStore.class);
        CurrentUser current = mock(CurrentUser.class);
        TokenService tokens = mock(TokenService.class);
        UUID user = UUID.randomUUID();
        when(current.id()).thenReturn(user);
        when(current.hasRole("ADMIN")).thenReturn(false);
        when(db.one(contains("SELECT role FROM users"), eq(user))).thenReturn(Map.of("role", "ADMIN"));
        when(db.list(contains("role='ADMIN'"))).thenReturn(List.of(Map.of("id", user)));
        var service = new ProfileService(
            new ProfileRepository(db, mock(UserJpaRepository.class)),
            current,
            tokens,
            mock(PasswordEncoder.class),
            mock(ResumeProfileAlignmentRefreshService.class)
        );
        assertEquals(409, assertThrows(ApiException.class, service::deleteAccount).status());
        var order = inOrder(db);
        order.verify(db).scalar(contains("ACCOUNT_ADMIN_MEMBERSHIP"));
        order.verify(db).one(contains("SELECT role FROM users"), eq(user));
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(tokens);
    }
}
