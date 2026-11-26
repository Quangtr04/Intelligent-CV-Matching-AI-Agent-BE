package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.ManagerQuotaLimitsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.billing.SubscriptionQuotaRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerQuotaRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.AdminService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.ManagerQuotaPolicy;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ManagerQuotaPolicyTests {

    private final UUID manager = UUID.randomUUID();

    private final ManagerQuotaRepository db = mock(ManagerQuotaRepository.class);

    private final CurrentUser current = new CurrentUser();

    private final AuditService audit = mock(AuditService.class);

    private final ManagerQuotaPolicy policy = new ManagerQuotaPolicy(db, current, audit, 10000000, 100000);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(manager, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))
        );
    }

    @Test
    void managerGetsBoundedCatalogWithoutInfrastructureSettings() {
        login("MANAGER");
        Map<String, Object> limits = policy.ownLimits();
        assertEquals(10000000L, limits.get("maxTokenQuota"));
        assertEquals(100000L, limits.get("maxApiQuota"));
        assertEquals("DEFAULT_POLICY", limits.get("source"));
        List<?> codes = (List<?>) limits.get("allowedFeatureCodes");
        assertEquals(
            2 + 2 * com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog.descriptors().size(),
            codes.size()
        );
        assertTrue(codes.containsAll(List.of("APPLICATION_REVIEW", "API_CALLS_APPLICATION_REVIEW")));
        assertTrue(
            codes.containsAll(
                List.of(
                    "AI_TOTAL_TOKENS",
                    "CV_ANALYZE",
                    "API_CALLS_TOTAL",
                    "API_CALLS_JOB_ATS",
                    "JOB_REQUIREMENT_EVIDENCE",
                    "API_CALLS_JOB_REQUIREMENT_EVIDENCE",
                    "JD_SKILL_PROFICIENCY",
                    "API_CALLS_JD_SKILL_PROFICIENCY"
                )
            )
        );
        assertDoesNotThrow(
            () -> policy.validate(
                List.of(
                    new SubscriptionQuotaRequest("CV_ANALYZE", 10000000, "MONTHLY"),
                    new SubscriptionQuotaRequest("API_CALLS_JOB_ATS", 100000, "SUBSCRIPTION"),
                    new SubscriptionQuotaRequest("JOB_REQUIREMENT_EVIDENCE", 10000000, "MONTHLY"),
                    new SubscriptionQuotaRequest("API_CALLS_JOB_REQUIREMENT_EVIDENCE", 100000, "SUBSCRIPTION")
                )
            )
        );
    }

    @Test
    void overrideAndUnitsAreEnforcedBeforeSubscriptionWrites() {
        login("MANAGER");
        when(db.limits(manager)).thenReturn(Optional.of(Map.of("max_token_quota", 500L, "max_api_quota", 2L)));
        assertEquals("ADMIN_OVERRIDE", policy.ownLimits().get("source"));
        for (SubscriptionQuotaRequest invalid : List.of(
            new SubscriptionQuotaRequest("AI_TOTAL_TOKENS", 501, "MONTHLY"),
            new SubscriptionQuotaRequest("API_CALLS_TOTAL", 3, "MONTHLY"),
            new SubscriptionQuotaRequest("CV_ANALYZE", -1, "MONTHLY"),
            new SubscriptionQuotaRequest("UNKNOWN", 1, "MONTHLY"),
            new SubscriptionQuotaRequest("CV_BUILD", 1, "LIFETIME")
        )) {
            AdminRepository subscriptions = mock(AdminRepository.class);
            AdminService service = new AdminService(subscriptions, current, audit, null, null, null, policy);
            UUID tier = UUID.randomUUID();
            assertEquals(403, assertThrows(ApiException.class, () -> service.quotas(tier, List.of(invalid))).status());
            verify(subscriptions).getSubscriptionTiersById(tier);
            verifyNoMoreInteractions(subscriptions);
        }
    }

    @Test
    void onlyAdministratorCanChangeDelegatedLimits() {
        for (String role : List.of("MANAGER", "CANDIDATE", "CAREER_CONSULTANT")) {
            login(role);
            assertEquals(
                403,
                assertThrows(ApiException.class, () -> policy.update(manager, new ManagerQuotaLimitsRequest(1000, 10)))
                    .status()
            );
        }
        verifyNoInteractions(db, audit);
        login("ADMIN");
        policy.update(manager, new ManagerQuotaLimitsRequest(1000, 10));
        verify(db).requireManager(manager);
        verify(db).save(manager, 1000, 10, manager);
        verify(audit).record(eq("UPDATE_MANAGER_QUOTA_LIMITS"), eq("USER"), eq(manager), any(), any());
    }

    @Test
    void administratorRetainsBroaderQuotaPeriods() {
        login("ADMIN");
        assertDoesNotThrow(
            () -> policy.validate(List.of(new SubscriptionQuotaRequest("CV_BUILD", 20000000, "YEARLY")))
        );
        verifyNoInteractions(db);
    }

    @Test
    void missingPolicyFailsClosedForManager() {
        login("MANAGER");
        AdminRepository subscriptions = mock(AdminRepository.class);
        AdminService service = new AdminService(subscriptions, current, audit, null, null);
        UUID tier = UUID.randomUUID();
        assertEquals(503, assertThrows(ApiException.class, () -> service.quotas(tier, List.of())).status());
        verify(subscriptions).getSubscriptionTiersById(tier);
        verifyNoMoreInteractions(subscriptions);
    }
}
