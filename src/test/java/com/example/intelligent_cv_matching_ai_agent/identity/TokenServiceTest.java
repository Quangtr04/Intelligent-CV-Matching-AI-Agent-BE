package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    @Test
    void refreshTokensCannotBeUsedAsBearerAccess() {
        SqlStore db = mock(SqlStore.class);
        assertEquals(
            401,
            assertThrows(
                ApiException.class,
                () -> new TokenService(new TokenRepository(db)).authenticate(TokenService.random("r_"))
            ).status()
        );
        verifyNoInteractions(db);
    }

    @Test
    void sessionSecretsAreStoredOnlyAsHashes() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        when(db.optional(contains("FROM users"), eq(user))).thenReturn(Optional.of(Map.of("id", user)));
        var result = new TokenService(new TokenRepository(db)).issue(user);
        verify(db, times(2)).insert(
            eq("refresh_tokens"),
            argThat(
                fields -> fields.get("token_hash").toString().matches("[0-9a-f]{64}")
                    && !fields.containsValue(result.get("accessToken"))
                    && !fields.containsValue(result.get("refreshToken"))
            )
        );
    }

    @Test
    void inactiveAccountCannotReceiveNewSessionsAfterRevocation() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        when(db.optional(anyString(), eq(user))).thenReturn(Optional.empty());
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new TokenService(new TokenRepository(db)).issue(user)).status()
        );
        verify(db).optional(contains("FOR NO KEY UPDATE"), eq(user));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void refreshRechecksRevocationAfterAcquiringAccountLock() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        String raw = TokenService.random("r_");
        when(db.optional(startsWith("SELECT user_id FROM refresh_tokens"), eq(TokenService.hash(raw))))
            .thenReturn(Optional.of(Map.of("user_id", user)));
        when(db.optional(contains("FROM users"), eq(user))).thenReturn(Optional.of(Map.of("id", user)));
        when(db.optional(startsWith("SELECT t.id,t.user_id"), eq(TokenService.hash(raw)))).thenReturn(Optional.empty());
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> new TokenService(new TokenRepository(db)).rotate(raw)).status()
        );
        var order = inOrder(db);
        order.verify(db).optional(startsWith("SELECT user_id FROM refresh_tokens"), eq(TokenService.hash(raw)));
        order.verify(db).optional(contains("FOR NO KEY UPDATE"), eq(user));
        order.verify(db).optional(contains("FOR UPDATE OF t"), eq(TokenService.hash(raw)));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void expiredOrSuspendedSessionCannotAuthenticate() {
        SqlStore db = mock(SqlStore.class);
        when(db.optional(anyString(), any())).thenReturn(Optional.empty());
        assertEquals(
            401,
            assertThrows(
                ApiException.class,
                () -> new TokenService(new TokenRepository(db)).authenticate(TokenService.random("a_"))
            ).status()
        );
        verify(db).optional(contains("u.status='ACTIVE'"), any());
    }
}
