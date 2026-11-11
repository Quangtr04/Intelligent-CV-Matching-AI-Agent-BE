package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.ManagerOperationsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerOperationsServiceTests {

    private final ManagerOperationsRepository db = mock(ManagerOperationsRepository.class);

    private final AdminService admin = mock(AdminService.class);

    private final ManagerOperationsService service = new ManagerOperationsService(db, admin, new CurrentUser());

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private void login(
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    @Test
    void candidateAndConsultantCannotReadManagerReports() {
        for (String role : List.of("CANDIDATE", "CAREER_CONSULTANT")) {
            login(role);
            assertEquals(403, assertThrows(ApiException.class, service::dashboard).status());
            assertEquals(403, assertThrows(ApiException.class, () -> service.payments(null, "", 20, 0)).status());
            assertEquals(403, assertThrows(ApiException.class, () -> service.consultants("", 20, 0)).status());
        }
        verifyNoInteractions(db, admin);
    }

    @Test
    void managerAndAdminDashboardIncludeBookingServiceQuality() {
        when(admin.dashboard()).thenReturn(Map.of("total_users", 5));
        when(db.serviceMetrics()).thenReturn(Map.of("active_consultants", 2));
        for (String role : List.of("ADMIN", "MANAGER")) {
            login(role);
            Map<String, Object> response = service.dashboard();
            assertEquals(5, response.get("total_users"));
            assertEquals(Map.of("active_consultants", 2), response.get("service_metrics"));
        }
    }

    @Test
    void reportsUseVietnamDateBoundariesAndIncludeTheWholeLastDay() {
        login("MANAGER");
        LocalDate date = LocalDate.of(2026, 11, 7);
        Map<String, Object> response = service.analytics(date, date);
        OffsetDateTime start = OffsetDateTime.parse("2026-11-07T00:00:00+07:00");
        OffsetDateTime until = OffsetDateTime.parse("2026-11-08T00:00:00+07:00");
        assertEquals("Asia/Ho_Chi_Minh", response.get("time_zone"));
        verify(db).dailyUsage(start, until);
        verify(db).dailyRevenue(start, until);
        verify(db).consultationSummary(start, until);
    }

    @Test
    void invalidFiltersAndUnboundedReportsAreRejectedBeforeQuerying() {
        login("MANAGER");
        LocalDate date = LocalDate.of(2026, 11, 7);
        assertEquals(400, assertThrows(ApiException.class, () -> service.analytics(date, date.minusDays(1))).status());
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.analytics(date.minusDays(366), date)).status()
        );
        assertEquals(400, assertThrows(ApiException.class, () -> service.payments("UNKNOWN", "", 20, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.usage(date, date, "' SQL", 20, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.invoices(null, "", 101, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.crawlReports(null, null, 20, -1)).status());
        verifyNoInteractions(db);
    }

    @Test
    void detailProjectionsJoinTheApplicableInvoiceAndPlanQuotas() {
        login("MANAGER");
        UUID payment = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        UUID tier = UUID.randomUUID();
        when(db.payment(payment)).thenReturn(Map.of("id", payment));
        when(db.paymentInvoice(payment)).thenReturn(java.util.Optional.of(Map.of("invoice_number", "SYNTHETIC")));
        when(db.subscription(subscription)).thenReturn(Map.of("id", subscription, "tier_id", tier));
        when(db.quotas(tier)).thenReturn(List.of(Map.of("feature_code", "AI_TOTAL_TOKENS", "quota_value", 1000)));
        assertEquals(Map.of("invoice_number", "SYNTHETIC"), service.payment(payment).get("invoice"));
        assertEquals(db.quotas(tier), service.subscription(subscription).get("quotas"));
    }
}
