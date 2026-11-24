package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntitlementServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MANAGER", "CAREER_CONSULTANT"})
    void staffCanReserveWithoutSubscriptionQuotasAndUsageIsStillRecorded(
        String role) {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID(), reservation = UUID.randomUUID();
        when(db.one(contains("FOR NO KEY UPDATE"), eq(user))).thenReturn(Map.of("id", user, "role", role));
        when(db.insert(eq("ai_token_reservations"), anyMap())).thenReturn(reservation);
        var service = new EntitlementService(new EntitlementRepository(db));

        assertEquals(reservation, service.reserve(user, "CV_ANALYZE", 20000));
        verify(db).one(contains("SELECT id,role FROM users"), eq(user));
        verify(db, never()).optional(anyString(), any(Object[].class));
        verify(db, never()).scalar(anyString(), any(Object[].class));
        verify(db).insert(
            eq("ai_token_reservations"),
            argThat(
                row -> user.equals(row.get("user_id")) && "CV_ANALYZE".equals(row.get("feature_code"))
                    && Integer.valueOf(20000).equals(row.get("reserved_tokens")) && "RESERVED".equals(row.get("status"))
            )
        );

        when(db.one(anyString(), eq(reservation))).thenReturn(Map.of("status", "RESERVED", "reserved_tokens", 20000));
        service.complete(reservation, 15000);
        verify(db).update(contains("consumed_tokens=?,status='COMPLETED'"), eq(15000), eq(reservation));
        service.cancel(reservation);
        verify(db).update(contains("status='CANCELLED'"), eq(reservation));
    }

    @Test
    void inFlightReservationsCountAgainstGlobalBudgetBeforeProviderCall() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID(), tier = UUID.randomUUID();
        when(db.one(contains("FOR NO KEY UPDATE"), eq(user))).thenReturn(Map.of("id", user));
        when(db.optional(contains("user_subscriptions"), eq(user))).thenReturn(Optional.of(Map.of("id", tier)));
        when(db.optional(contains("AI_TOTAL_TOKENS"), eq(tier)))
            .thenReturn(Optional.of(Map.of("quota_value", 10000L, "quota_period", "MONTHLY")));
        when(db.optional(contains("feature_code=?"), eq(tier), eq("RESUME_BUILDER"))).thenReturn(Optional.empty());
        when(db.scalar(contains("status IN ('RESERVED','COMPLETED')"), eq(user))).thenReturn(9500L);
        when(db.scalar(contains("FROM user_token_wallets"), eq(user))).thenReturn(0L);
        assertEquals(
            429,
            assertThrows(
                ApiException.class,
                () -> new EntitlementService(new EntitlementRepository(db)).reserve(user, "RESUME_BUILDER", 1000)
            ).status()
        );
        verify(db, never()).insert(eq("ai_token_reservations"), anyMap());
    }

    @Test
    void explicitZeroFeatureQuotaRejectsEvenWhenGlobalBudgetIsAvailable() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID(), tier = UUID.randomUUID();
        when(db.one(anyString(), eq(user))).thenReturn(Map.of("id", user));
        when(db.optional(contains("user_subscriptions"), eq(user))).thenReturn(Optional.of(Map.of("id", tier)));
        when(db.optional(contains("AI_TOTAL_TOKENS"), eq(tier)))
            .thenReturn(Optional.of(Map.of("quota_value", 100000L)));
        when(db.optional(contains("feature_code=?"), eq(tier), eq("APPLICATION_EMAIL")))
            .thenReturn(Optional.of(Map.of("quota_value", 0L)));
        assertEquals(
            403,
            assertThrows(
                ApiException.class,
                () -> new EntitlementService(new EntitlementRepository(db)).reserve(user, "APPLICATION_EMAIL", 1000)
            ).status()
        );
        verify(db, never()).insert(eq("ai_token_reservations"), anyMap());
    }

    @Test
    void freeRoadmapRequiresPurchasedWalletTokens() {
        var db = mock(EntitlementRepository.class);
        UUID user = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        when(db.getUsersByIdForUpdate(user)).thenReturn(Map.of("role", "CANDIDATE"));
        when(db.findUserSubscriptionsByUserId(user)).thenReturn(Optional.empty());
        when(db.findSubscriptionTiersByTierCode()).thenReturn(Optional.of(Map.of("tier_code", "FREE")));
        var service = new EntitlementService(db);

        assertEquals(402, assertThrows(ApiException.class, () -> service.requireRoadmapAccess(user)).status());
        assertEquals(
            402,
            assertThrows(ApiException.class, () -> service.reserve(user, "ROADMAP_GENERATE", 1000)).status()
        );
        verify(db, never()).insertAiTokenReservations(anyMap());

        when(db.walletBalance(user)).thenReturn(1000L);
        when(db.reserveWalletTokens(user, 1000)).thenReturn(1);
        when(db.insertAiTokenReservations(anyMap())).thenReturn(reservation);
        assertDoesNotThrow(() -> service.requireRoadmapAccess(user));
        assertEquals(reservation, service.reserve(user, "ROADMAP_GENERATE", 1000));
        verify(db)
            .insertAiTokenReservations(argThat(row -> Long.valueOf(1000).equals(row.get("wallet_reserved_tokens"))));
    }

    @Test
    void completionCannotChargeBeyondReservedBudget() {
        SqlStore db = mock(SqlStore.class);
        UUID reservation = UUID.randomUUID();
        when(db.one(anyString(), eq(reservation))).thenReturn(Map.of("status", "RESERVED", "reserved_tokens", 2000L));
        assertThrows(
            ApiException.class,
            () -> new EntitlementService(new EntitlementRepository(db)).complete(reservation, 2001)
        );
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
