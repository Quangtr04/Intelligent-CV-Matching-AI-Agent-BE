package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionTierRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerStateScenarioTests {

    private final UUID manager = UUID.randomUUID();

    private final UUID id = UUID.randomUUID();

    private final CurrentUser user = new CurrentUser();

    private final AdminRepository plans = mock(AdminRepository.class);

    private final AuditService audit = mock(AuditService.class);

    private final AdminService catalog = new AdminService(plans, user, audit, null, null);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paidPlanActivationChangesOnlyCatalogMetadataAndRecordsBeforeAndAfter(
        boolean active) {
        login(manager, "MANAGER");
        Map<String, Object> before =
            Map.of("id", id, "tier_code", "PRO", "tier_type", "SUBSCRIPTION", "is_active", !active);
        Map<String, Object> after =
            Map.of("id", id, "tier_code", "PRO", "tier_type", "SUBSCRIPTION", "is_active", active);
        when(plans.getSubscriptionTiersById(id)).thenReturn(before, after);
        var request = new SubscriptionTierRequest("PRO", "Professional", new BigDecimal("199000"), "VND", active);

        assertSame(after, catalog.editTier(id, request));

        var order = inOrder(plans, audit);
        order.verify(plans).getSubscriptionTiersById(id);
        order.verify(plans).updateSubscriptionTiersById(
            "PRO",
            "Professional",
            new BigDecimal("199000"),
            "VND",
            active,
            "SUBSCRIPTION",
            0L,
            id
        );
        order.verify(plans).getSubscriptionTiersById(id);
        order.verify(audit).record("UPDATE_TIER", "SUBSCRIPTION_TIER", id, before, after);
        verifyNoMoreInteractions(plans, audit);
    }

    @Test
    void freePlanCannotBeDeactivatedOrTurnedIntoAPaidPlan() {
        login(manager, "MANAGER");
        when(plans.getSubscriptionTiersById(id)).thenReturn(Map.of("id", id, "tier_code", "FREE"));
        for (SubscriptionTierRequest invalid : List.of(
            new SubscriptionTierRequest("FREE", "Free", BigDecimal.ZERO, "VND", false),
            new SubscriptionTierRequest("FREE", "Free", BigDecimal.ONE, "VND", true),
            new SubscriptionTierRequest("PRO", "Pro", BigDecimal.ZERO, "VND", true)
        )) {
            assertEquals(400, assertThrows(ApiException.class, () -> catalog.editTier(id, invalid)).status());
        }

        verify(plans, never())
            .updateSubscriptionTiersById(any(), any(), any(), any(), anyBoolean(), any(), anyLong(), any());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CAREER_CONSULTANT"})
    void nonOperatorsCannotChangePlanAvailability(
        String role) {
        login(manager, role);

        assertEquals(
            403,
            assertThrows(
                ApiException.class,
                () -> catalog.editTier(id, new SubscriptionTierRequest("PRO", "Pro", BigDecimal.ONE, "VND", false))
            ).status()
        );

        verifyNoInteractions(plans, audit);
    }

    private void login(
        UUID actor,
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor.toString(),
                "",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }
}
