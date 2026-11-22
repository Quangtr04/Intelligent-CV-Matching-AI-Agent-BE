package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.StaffOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.StaffOperationsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaffOperationsServiceTests {

    private final StaffOperationsRepository db = mock(StaffOperationsRepository.class);

    private final StaffOperationsService service = new StaffOperationsService(db, new CurrentUser());

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
    void candidateAndConsultantCannotReadStaffDashboardOrRatings() {
        for (String role : List.of("CANDIDATE", "CAREER_CONSULTANT")) {
            login(role);
            assertEquals(403, assertThrows(ApiException.class, service::dashboard).status());
            assertEquals(403, assertThrows(ApiException.class, () -> service.consultants("", 20, 0)).status());
            assertEquals(
                403,
                assertThrows(ApiException.class, () -> service.consultantRatings(null, null, false, 20, 0)).status()
            );
        }
        verifyNoInteractions(db);
    }

    @Test
    void staffManagerAndAdminCanAccessStaffOperationalViews() {
        when(db.dashboard()).thenReturn(Map.of("active_jobs", 10, "pending_rating_appeals", 2));
        for (String role : List.of("STAFF", "MANAGER", "ADMIN")) {
            login(role);
            Map<String, Object> response = service.dashboard();
            assertEquals(10, response.get("active_jobs"));
            assertEquals(2, response.get("pending_rating_appeals"));
        }
    }

    @Test
    void invalidFiltersAreRejectedBeforeQuerying() {
        login("STAFF");
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.consultantRatings(null, 0, false, 20, 0)).status()
        );
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.consultantRatings(null, 6, false, 20, 0)).status()
        );
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.crawlReports("UNKNOWN", null, 20, 0)).status()
        );
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.crawlReports("ITVIEC", "INVALID", 20, 0)).status()
        );
        assertEquals(400, assertThrows(ApiException.class, () -> service.feedback("INVALID_SOURCE", 20, 0)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.feedback("INVALID_SOURCE", 20, 0)).status());
        verifyNoInteractions(db);
    }

    @Test
    void consultantRatingsForwardNormalizedFiltersToRepository() {
        login("STAFF");
        UUID consultantId = UUID.randomUUID();
        service.consultantRatings(consultantId, 2, true, 15, 5);
        verify(db).consultantRatings(consultantId, 2, true, 15, 5);
    }
}
