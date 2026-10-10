package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntitlementServiceTest {

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
