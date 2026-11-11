package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class ManagerRoleAuthorizationTests {

    private final CurrentUser current = new CurrentUser();

    @AfterEach
    void clearContext() {
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
    void managerHasOperatingAccessWithoutAdministratorAuthority() {
        login("MANAGER");
        assertTrue(current.hasOperationsRole());
        assertDoesNotThrow(current::requireOperationsRole);
        assertFalse(current.hasRole("ADMIN"));
        assertFalse(current.isStrictAdmin());
        assertEquals(403, assertThrows(ApiException.class, () -> current.requireRole("ADMIN")).status());
    }

    @Test
    void candidateAndConsultantCannotOperateThePlatform() {
        for (String role : List.of("CANDIDATE", "CAREER_CONSULTANT")) {
            login(role);
            assertFalse(current.hasOperationsRole());
            assertFalse(current.hasRole("ADMIN"));
            assertEquals(403, assertThrows(ApiException.class, current::requireOperationsRole).status());
        }
    }

    @Test
    void administratorKeepsBothOperatingAndAdministrativeAuthority() {
        login("ADMIN");
        assertTrue(current.hasOperationsRole());
        assertTrue(current.isStrictAdmin());
        assertDoesNotThrow(current::requireOperationsRole);
    }
}
