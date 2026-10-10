package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.SubscriptionMaintenanceRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SubscriptionMaintenance;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionMaintenanceTest {

    @Test
    void databaseUniqueReceiptPreventsDuplicateNotificationOnRepeatedRuns() {
        SqlStore db = mock(SqlStore.class);
        UUID subscription = UUID.randomUUID(), user = UUID.randomUUID();
        when(
            db.update(
                contains("ON CONFLICT(subscription_id,notification_type) DO NOTHING"),
                eq(user),
                eq(subscription),
                eq("RENEWAL_7D")
            )
        ).thenReturn(1, 0);
        SubscriptionMaintenance maintenance =
            new SubscriptionMaintenance(new SubscriptionMaintenanceRepository(db), true, 500);
        Map<String, Object> row = Map.of("id", subscription, "user_id", user);
        assertTrue(maintenance.notifyOnce(row, "RENEWAL_7D", "Renew soon", "Seven days remaining"));
        assertFalse(maintenance.notifyOnce(row, "RENEWAL_7D", "Renew soon", "Seven days remaining"));
        verify(db, times(1)).insert(eq("notifications"), anyMap());
    }

    @Test
    void disablingMaintenanceAvoidsAllDatabaseWork() {
        SqlStore db = mock(SqlStore.class);
        new SubscriptionMaintenance(new SubscriptionMaintenanceRepository(db), false, 500).maintain();
        verifyNoInteractions(db);
    }
}
