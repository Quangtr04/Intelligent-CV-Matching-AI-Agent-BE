package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.repository.guest.identity.GuestAccessRepository;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestAccessTest {

    @Test
    void reservationRemainsProcessingUntilCallFinishes() {
        SqlStore db = mock(SqlStore.class);
        UUID guest = UUID.randomUUID(), event = UUID.randomUUID();
        when(db.update(contains("token_used=token_used+"), eq(2000), eq(guest), eq(2000))).thenReturn(1);
        when(db.insert(eq("trial_usage_events"), anyMap())).thenReturn(event);
        assertEquals(event, new GuestAccess(new GuestAccessRepository(db)).reserve(guest, "CV_BUILD", 2000, null));
        verify(db).insert(
            eq("trial_usage_events"),
            argThat(
                fields -> "PROCESSING".equals(fields.get("status"))
                    && Integer.valueOf(2000).equals(fields.get("tokens_used"))
            )
        );
    }

    @Test
    void settlementRefundsOnlyItsReservationAndRecordsActualUsage() {
        SqlStore db = mock(SqlStore.class);
        UUID guest = UUID.randomUUID(), event = UUID.randomUUID();
        when(db.one(anyString(), eq(event)))
            .thenReturn(Map.of("guest_session_id", guest, "status", "PROCESSING", "tokens_used", 2000));
        when(db.update(contains("token_used=token_used-"), eq(1400), eq(guest), eq(1400))).thenReturn(1);
        new GuestAccess(new GuestAccessRepository(db)).settle(event, 600, true);
        verify(db).update(contains("trial_usage_events SET tokens_used"), eq(600), eq("COMPLETED"), eq(event));
    }

    @Test
    void invalidModelOutputKeepsChargedProviderUsageAndRecordsFailure() {
        SqlStore db = mock(SqlStore.class);
        UUID guest = UUID.randomUUID(), event = UUID.randomUUID();
        when(db.one(anyString(), eq(event)))
            .thenReturn(Map.of("guest_session_id", guest, "status", "PROCESSING", "tokens_used", 2000));
        when(db.update(contains("token_used=token_used-"), eq(1500), eq(guest), eq(1500))).thenReturn(1);
        new GuestAccess(new GuestAccessRepository(db)).settle(event, 500, false);
        verify(db).update(contains("trial_usage_events SET tokens_used"), eq(500), eq("FAILED"), eq(event));
    }

    @Test
    void finalizedSettlementRetryCannotRefundTwice() {
        SqlStore db = mock(SqlStore.class);
        UUID event = UUID.randomUUID();
        when(db.one(anyString(), eq(event))).thenReturn(Map.of("status", "COMPLETED", "tokens_used", 600));
        new GuestAccess(new GuestAccessRepository(db)).settle(event, 600, true);
        verify(db, never()).update(anyString(), any(Object[].class));
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> new GuestAccess(new GuestAccessRepository(db)).settle(event, 0, false)
            ).status()
        );
    }

    @Test
    void settlementCannotChargeBeyondReservation() {
        SqlStore db = mock(SqlStore.class);
        UUID event = UUID.randomUUID();
        when(db.one(anyString(), eq(event))).thenReturn(Map.of("status", "PROCESSING", "tokens_used", 2000));
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> new GuestAccess(new GuestAccessRepository(db)).settle(event, 2001, true)
            ).status()
        );
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
