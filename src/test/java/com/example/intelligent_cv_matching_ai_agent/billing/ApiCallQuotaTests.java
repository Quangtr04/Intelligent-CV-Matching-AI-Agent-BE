package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ApiCallQuotaTests {

    private final EntitlementRepository db = mock(EntitlementRepository.class);

    private final EntitlementService service = new EntitlementService(db);

    private final UUID user = UUID.randomUUID();

    private final UUID tier = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(db.getUsersByIdForUpdate(user)).thenReturn(Map.of("id", user, "role", "CANDIDATE"));
        when(db.findUserSubscriptionsByUserId(user)).thenReturn(Optional.of(Map.of("id", tier)));
        when(db.findSubscriptionQuotasByTierId(tier)).thenReturn(Optional.of(Map.of("quota_value", 10000L)));
    }

    @Test
    void absentCallRulesPreserveExistingTokenBudgets() {
        service.reserve(user, "CV_ANALYZE", 1000);
        verify(db).insertAiTokenReservations(anyMap());
        verify(db, never()).requestsUsed(any(), any(), any());
    }

    @Test
    void globalCallQuotaStopsProviderReservationEvenWithAvailableTokens() {
        when(db.findSubscriptionQuotasByTierId2(tier, "API_CALLS_TOTAL"))
            .thenReturn(Optional.of(Map.of("quota_value", 2L, "quota_period", "MONTHLY")));
        when(db.requestsUsed(user, null, "month")).thenReturn(2L);
        assertEquals(429, assertThrows(ApiException.class, () -> service.reserve(user, "CV_ANALYZE", 1)).status());
        verify(db, never()).insertAiTokenReservations(anyMap());
        var order = inOrder(db);
        order.verify(db).getUsersByIdForUpdate(user);
        order.verify(db).requestsUsed(user, null, "month");
    }

    @Test
    void featureCallQuotaUsesItsOwnFeatureAndPeriod() {
        when(db.findSubscriptionQuotasByTierId2(tier, "API_CALLS_JOB_ATS"))
            .thenReturn(Optional.of(Map.of("quota_value", 1L, "quota_period", "SUBSCRIPTION")));
        when(db.requestsUsed(user, "JOB_ATS", "subscription")).thenReturn(1L);
        assertEquals(429, assertThrows(ApiException.class, () -> service.reserve(user, "JOB_ATS", 100)).status());
        verify(db, never()).insertAiTokenReservations(anyMap());
    }

    @Test
    void zeroCallLimitDeniesFirstRequest() {
        when(db.findSubscriptionQuotasByTierId2(tier, "API_CALLS_TOTAL"))
            .thenReturn(Optional.of(Map.of("quota_value", 0L)));
        assertEquals(429, assertThrows(ApiException.class, () -> service.reserve(user, "CV_BUILD", 100)).status());
        verify(db, never()).insertAiTokenReservations(anyMap());
    }
}
