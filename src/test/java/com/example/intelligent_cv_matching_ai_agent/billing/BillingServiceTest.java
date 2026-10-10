package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.BillingService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    @Test
    void mismatchedPaymentAmountCannotActivateSubscription() {
        SqlStore db = mock(SqlStore.class);
        UUID id = UUID.randomUUID();
        when(db.one(anyString(), eq(id))).thenReturn(
            Map.of(
                "id",
                id,
                "payment_provider",
                "TEST",
                "provider_order_id",
                id.toString(),
                "amount",
                new BigDecimal("100.00"),
                "currency",
                "VND",
                "status",
                "PENDING"
            )
        );
        BillingService service = new BillingService(new BillingRepository(db), "TEST");
        assertThrows(
            ApiException.class,
            () -> service.webhook(
                new PaymentWebhookRequest(id, id.toString(), "evt1", "COMPLETED", new BigDecimal("1.00"), "VND")
            )
        );
        verify(db, never()).insert(eq("user_subscriptions"), anyMap());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void browserOrderIgnoresAnyClientAmountAndUsesTierPrice() {
        SqlStore db = mock(SqlStore.class);
        UUID user = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(db.one(contains("subscription_tiers"), eq(tier)))
            .thenReturn(Map.of("id", tier, "price_monthly", new BigDecimal("99000.00"), "currency", "VND"));
        when(db.one(contains("payment_orders"), any(UUID.class))).thenReturn(Map.of("status", "PENDING"));
        new BillingService(new BillingRepository(db), "TEST").order(user, tier, 3);
        verify(db).insert(
            eq("payment_orders"),
            argThat(
                fields -> new BigDecimal("297000.00").compareTo((BigDecimal) fields.get("amount")) == 0
                    && "PENDING".equals(fields.get("status"))
            )
        );
        verify(db, never()).insert(eq("user_subscriptions"), anyMap());
    }

    @Test
    void successfulEventReplayDoesNotActivateTwice() {
        SqlStore db = mock(SqlStore.class);
        UUID id = UUID.randomUUID();
        when(db.one(contains("payment_orders"), eq(id))).thenReturn(
            Map.of(
                "id",
                id,
                "payment_provider",
                "TEST",
                "provider_order_id",
                id.toString(),
                "amount",
                new BigDecimal("100.00"),
                "currency",
                "VND",
                "status",
                "COMPLETED"
            )
        );
        when(db.one(contains("payment_webhook_events"), eq("TEST"), eq("evt1")))
            .thenReturn(Map.of("payment_order_id", id));
        when(db.update(contains("payment_webhook_events"), eq("TEST"), eq("evt1"), eq(id))).thenReturn(0);
        Map<String, Object> result = new BillingService(new BillingRepository(db), "TEST").webhook(
            new PaymentWebhookRequest(id, id.toString(), "evt1", "COMPLETED", new BigDecimal("100.00"), "VND")
        );
        assertEquals(true, result.get("duplicate"));
        verify(db, never()).insert(eq("user_subscriptions"), anyMap());
    }
}
