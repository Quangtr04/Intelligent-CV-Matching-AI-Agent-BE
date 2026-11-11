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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerReportingScenarioTests {

    private final ManagerOperationsRepository db = mock(ManagerOperationsRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ManagerOperationsService service = new ManagerOperationsService(db, mock(AdminService.class), user);

    @BeforeEach
    void identity() {
        when(user.id()).thenReturn(UUID.randomUUID());
        when(user.hasRole("MANAGER")).thenReturn(true);
    }

    @Test
    void allOperationalListsPreservePaginationBeyondTheFirstHundredRowsAndNormalizeFilters() {
        service.subscriptions(" active ", " Candidate ", 20, 120);
        service.payments(" completed ", " Order42 ", 20, 140);
        service.invoices(" paid ", " Invoice42 ", 20, 160);
        service.consultants(" java ", 20, 180);
        service.feedback(20, 200);
        service.crawlReports(" topcv ", " partial ", 20, 220);

        verify(db).subscriptions("ACTIVE", "%Candidate%", 20, 120);
        verify(db).payments("COMPLETED", "%Order42%", 20, 140);
        verify(db).invoices("PAID", "%Invoice42%", 20, 160);
        verify(db).consultants("%java%", 20, 180);
        verify(db).feedback(20, 200);
        verify(db).crawlReports("TOPCV", "PARTIAL", 20, 220);
    }

    @Test
    void usageUsesAnInclusiveEndDateAndCanonicalFeatureCodeWithTheRequestedPage() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);

        service.usage(from, to, " api_calls_job_ats ", 100, 300);

        verify(db).usage(
            OffsetDateTime.parse("2026-01-01T00:00:00+07:00"),
            OffsetDateTime.parse("2026-02-01T00:00:00+07:00"),
            "API_CALLS_JOB_ATS",
            100,
            300
        );
    }

    @Test
    void omittedStartDateUsesThirtyCalendarDaysEndingOnTheSelectedDay() {
        LocalDate to = LocalDate.of(2026, 11, 7);

        Map<String, Object> result = service.analytics(null, to);

        assertEquals(LocalDate.of(2026, 10, 9), result.get("from"));
        assertEquals(to, result.get("to"));
        verify(db).dailyUsage(
            OffsetDateTime.parse("2026-10-09T00:00:00+07:00"),
            OffsetDateTime.parse("2026-11-08T00:00:00+07:00")
        );
    }

    @Test
    void exactlyThreeHundredSixtySixInclusiveDaysIsAccepted() {
        LocalDate from = LocalDate.of(2025, 11, 7);
        LocalDate to = from.plusDays(365);

        assertDoesNotThrow(() -> service.analytics(from, to));

        verify(db).dailyRevenue(
            from.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime(),
            to.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    void invalidPageSizesNeverReachTheRepository(
        int limit) {
        assertEquals(400, assertThrows(ApiException.class, () -> service.feedback(limit, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.payments(null, null, limit, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.crawlReports(null, null, limit, 0)).status());
        verifyNoInteractions(db);
    }

    @Test
    void oversizedSearchAndUnknownSourceCannotReachOperationalQueries() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.consultants("x".repeat(256), 20, 0)).status());
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.crawlReports("OTHER", "FAILED", 20, 0)).status()
        );
        verifyNoInteractions(db);
    }

    @Test
    void bookingReportChecksTheSelectedConsultantBeforeQueryingBookings() {
        UUID consultant = UUID.randomUUID();
        when(db.consultant(consultant)).thenThrow(ApiException.notFound("Consultant not found"));

        assertEquals(
            404,
            assertThrows(ApiException.class, () -> service.consultantBookings(consultant, "ACCEPTED", 20, 0)).status()
        );

        verify(db, never()).consultantBookings(any(), any(), anyInt(), anyInt());
    }

    @Test
    void pendingPaymentWithoutAnInvoiceDoesNotBecomeAnIssuedInvoice() {
        UUID payment = UUID.randomUUID();
        when(db.payment(payment)).thenReturn(Map.of("id", payment, "status", "PENDING", "currency", "VND"));
        when(db.paymentInvoice(payment)).thenReturn(Optional.empty());

        Map<String, Object> result = service.payment(payment);

        assertEquals("PENDING", result.get("status"));
        assertEquals("VND", result.get("currency"));
        assertTrue(result.containsKey("invoice"));
        assertNull(result.get("invoice"));
    }

    @Test
    void unauthorizedActorCannotReachAnyNewOperationalListOrDetail() {
        when(user.hasRole("MANAGER")).thenReturn(false);
        UUID id = UUID.randomUUID();
        List<Runnable> actions = List.of(
            () -> service.subscriptions(null, null, 20, 0),
            () -> service.subscription(id),
            () -> service.invoices(null, null, 20, 0),
            () -> service.invoice(id),
            () -> service.payment(id),
            () -> service.usage(null, null, null, 20, 0),
            () -> service.consultantBookings(id, null, 20, 0),
            () -> service.crawlReports(null, null, 20, 0),
            () -> service.feedback(20, 0)
        );

        for (Runnable action : actions) {
            assertEquals(403, assertThrows(ApiException.class, action::run).status());
        }
        verifyNoInteractions(db);
    }
}
