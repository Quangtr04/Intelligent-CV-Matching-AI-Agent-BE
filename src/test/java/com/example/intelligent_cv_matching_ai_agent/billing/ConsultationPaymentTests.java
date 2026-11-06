package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.BillingService;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConsultationPaymentTests {

    final BillingRepository db = mock(BillingRepository.class);

    final BillingService service = new BillingService(db, "TEST");

    final UUID id = UUID.randomUUID();

    final Map<String, Object> order = new HashMap<>(
        Map.of(
            "id",
            id,
            "user_id",
            UUID.randomUUID(),
            "order_type",
            "CONSULTATION",
            "consultation_order_id",
            UUID.randomUUID(),
            "amount",
            new BigDecimal("49000"),
            "currency",
            "VND",
            "payment_provider",
            "TEST",
            "provider_order_id",
            id.toString(),
            "status",
            "PENDING"
        )
    );

    PaymentWebhookRequest event(
        String status,
        BigDecimal amount) {
        return new PaymentWebhookRequest(id, id.toString(), "evt", status, amount, "VND");
    }

    @Test
    void confirmedConsultationActivatesThreadAndInvoiceWithoutSubscription() {
        when(db.getPaymentOrdersByIdForUpdate(id)).thenReturn(order);
        when(db.updatePaymentWebhookEvents("TEST", "evt", id)).thenReturn(1);
        service.webhook(event("COMPLETED", new BigDecimal("49000")));
        verify(db).activateConsultation(order);
        verify(db).insertInvoices(
            argThat(
                invoice -> id.equals(invoice.get("payment_order_id"))
                    && new BigDecimal("49000").equals(invoice.get("total_amount"))
            )
        );
        verify(db, never()).insertUserSubscriptions(anyMap());
        verify(db, never()).updateUserSubscriptionsByUserId(any());
    }

    @Test
    void duplicateWebhookDoesNotActivateAnotherThread() {
        when(db.getPaymentOrdersByIdForUpdate(id)).thenReturn(order);
        when(db.getPaymentWebhookEventsByProvider("TEST", "evt")).thenReturn(Map.of("payment_order_id", id));
        assertEquals(true, service.webhook(event("COMPLETED", new BigDecimal("49000"))).get("duplicate"));
        verify(db, never()).activateConsultation(anyMap());
        verify(db, never()).insertInvoices(anyMap());
    }

    @Test
    void wrongAmountCannotReleaseConsultation() {
        when(db.getPaymentOrdersByIdForUpdate(id)).thenReturn(order);
        assertThrows(ApiException.class, () -> service.webhook(event("COMPLETED", BigDecimal.ONE)));
        verify(db, never()).activateConsultation(anyMap());
        verify(db, never()).updatePaymentWebhookEvents(any(), any(), any());
    }

    @Test
    void failedPaymentCancelsUnpaidConsultation() {
        when(db.getPaymentOrdersByIdForUpdate(id)).thenReturn(order);
        when(db.updatePaymentWebhookEvents("TEST", "evt", id)).thenReturn(1);
        service.webhook(event("FAILED", new BigDecimal("49000")));
        verify(db).updatePaymentOrdersById("FAILED", id);
        verify(db).cancelConsultation(order);
        verify(db, never()).activateConsultation(anyMap());
    }
}
