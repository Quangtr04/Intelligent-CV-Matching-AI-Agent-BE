package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation.ConsultationServiceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationCatalogRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SePayPaymentService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationCatalogService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConsultationCatalogServiceTests {

    final ConsultationCatalogRepository db = mock(ConsultationCatalogRepository.class);

    final BillingRepository billing = mock(BillingRepository.class);

    final SePayPaymentService sepay = mock(SePayPaymentService.class);

    final CurrentUser user = mock(CurrentUser.class);

    final ConsultationCatalogService service = new ConsultationCatalogService(db, billing, sepay, user);

    @Test
    void orderSnapshotsServerPriceAndNeverCreatesSubscription() {
        UUID candidate = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        when(user.id()).thenReturn(candidate);
        when(sepay.consultationProvider()).thenReturn("SEPAY_SANDBOX");
        Map<String, Object> catalog = new HashMap<>(
            Map.of(
                "service_code",
                "CAREER_CALL_30",
                "display_name",
                "30 minute session",
                "delivery_mode",
                "LIVE",
                "price",
                new BigDecimal("49000"),
                "currency",
                "VND",
                "is_active",
                true
            )
        );
        catalog.put("is_session_pack", true);
        catalog.put("duration_minutes", 30);
        catalog.put("sessions_count", 5);
        when(db.service(product)).thenReturn(catalog);
        when(db.insertOrder(anyMap())).thenReturn(order);
        Map<String, Object> result = service.order(new ConsultationOrderRequest(product, "Review", "My CV"));
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(db).insertOrder(snapshot.capture());
        assertEquals(new BigDecimal("49000"), snapshot.getValue().get("amount"));
        assertEquals("30 minute session", snapshot.getValue().get("service_name"));
        assertEquals(5, snapshot.getValue().get("sessions_total"));
        assertEquals(0, snapshot.getValue().get("sessions_remaining"));
        assertEquals(true, snapshot.getValue().get("is_session_pack"));
        assertEquals(candidate, snapshot.getValue().get("candidate_user_id"));
        assertEquals(order, result.get("id"));
        ArgumentCaptor<Map<String, Object>> payment = ArgumentCaptor.forClass(Map.class);
        verify(billing).insertPaymentOrders(payment.capture());
        assertEquals(order, payment.getValue().get("consultation_order_id"));
        assertEquals("CONSULTATION", payment.getValue().get("order_type"));
        assertFalse(payment.getValue().containsKey("tier_id"));
        verify(user).requireRole("CANDIDATE");
        verifyNoMoreInteractions(billing);
    }

    @Test
    void inactiveServiceCannotBeOrdered() {
        UUID id = UUID.randomUUID();
        when(db.service(id)).thenReturn(Map.of("is_active", false));
        assertThrows(ApiException.class, () -> service.order(new ConsultationOrderRequest(id, "Review", "My CV")));
        verify(db, never()).insertOrder(anyMap());
        verifyNoInteractions(billing);
    }

    @Test
    void checkoutMustBeConfiguredBeforeCreatingAnOrder() {
        when(sepay.consultationProvider()).thenThrow(new ApiException(503, "Not configured"));
        assertThrows(
            ApiException.class,
            () -> service.order(new ConsultationOrderRequest(UUID.randomUUID(), "Review", "My CV"))
        );
        verifyNoInteractions(db, billing);
    }

    @Test
    void liveServiceRequiresDurationAndAsyncServiceRejectsIt() {
        assertThrows(
            ApiException.class,
            () -> service
                .save(null, new ConsultationServiceRequest("LIVE_30", "Call", "", "LIVE", null, BigDecimal.TEN, true))
        );
        assertThrows(
            ApiException.class,
            () -> service.save(
                null,
                new ConsultationServiceRequest("CV_REVIEW", "Review", "", "ASYNC", 30, BigDecimal.TEN, true)
            )
        );
        verifyNoInteractions(db);
    }

    @Test
    void adminCatalogAndUpdatesRequireAdminRole() {
        doThrow(ApiException.forbidden("Role required")).when(user).requireOperationsRole();
        assertThrows(ApiException.class, service::adminServices);
        assertThrows(
            ApiException.class,
            () -> service.save(
                null,
                new ConsultationServiceRequest("CV_REVIEW", "Review", "", "ASYNC", null, BigDecimal.TEN, true)
            )
        );
        verifyNoInteractions(db);
    }

    @Test
    void cancellingUsesAuthenticatedCandidate() {
        UUID id = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        when(user.id()).thenReturn(candidate);
        service.cancel(id);
        verify(user).requireRole("CANDIDATE");
        verify(db).cancel(id, candidate);
    }

    @Test
    void legacyNonPackCannotCreateNewPaidRequests() {
        UUID id = UUID.randomUUID();
        when(db.service(id)).thenReturn(Map.of("is_active", true, "is_session_pack", false));
        assertThrows(ApiException.class, () -> service.order(new ConsultationOrderRequest(id, null, null)));
        verify(db, never()).insertOrder(anyMap());
        verifyNoInteractions(billing);
    }

    @Test
    void activeCatalogRejectsUnsupportedSessionLengthsAndAsyncProducts() {
        assertThrows(
            ApiException.class,
            () -> service
                .save(null, new ConsultationServiceRequest("CALL_45", "Call", "", "LIVE", 45, BigDecimal.TEN, true))
        );
        assertThrows(
            ApiException.class,
            () -> service
                .save(null, new ConsultationServiceRequest("ASYNC", "Review", "", "ASYNC", null, BigDecimal.TEN, true))
        );
        verifyNoInteractions(db);
    }
}
