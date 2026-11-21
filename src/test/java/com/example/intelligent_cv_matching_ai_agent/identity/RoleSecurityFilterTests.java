package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.config.shared.SecurityConfig;
import com.example.intelligent_cv_matching_ai_agent.security.shared.BearerFilter;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real filter chain without external databases or providers. */
class RoleSecurityFilterTests {

    private static AnnotationConfigWebApplicationContext context;

    private static MockMvc mvc;

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfig {

        @Bean
        BearerFilter bearer() {
            TokenService tokens = mock(TokenService.class);
            when(tokens.authenticate(anyString()))
                .thenAnswer(call -> Map.of("id", UUID.randomUUID(), "role", call.getArgument(0, String.class)));
            return new BearerFilter(tokens);
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {

        @RequestMapping("/api/**")
        Map<String, Object> ok() {
            return Map.of("ok", true);
        }
    }

    @BeforeAll
    static void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class, SecurityConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @Test
    void managerCanOperateButCannotConfigureSecurityOrAiInfrastructure() throws Exception {
        mvc.perform(post("/api/v1/admin/rag/documents/x/index").header("Authorization", "Bearer MANAGER"))
            .andExpect(status().isOk());
        for (String path : new String[]{
            "/api/v1/manager/analytics",
            "/api/v1/admin/subscription-tiers/x/quotas",
            "/api/v1/admin/announcements"}) {
            mvc.perform(get(path).header("Authorization", "Bearer MANAGER")).andExpect(status().isOk());
        }
        for (String path : new String[]{
            "/api/v1/admin/users",
            "/api/v1/admin/ai/runtime",
            "/api/v1/admin/ai/configurations",
            "/api/v1/admin/managers/x/quota-limits"}) {
            mvc.perform(put(path).header("Authorization", "Bearer MANAGER")).andExpect(status().isForbidden());
        }
    }

    @Test
    void retiredAndUnknownAccountRolesCannotAuthenticate() throws Exception {
        for (String role : new String[]{"MANAGEMENT", "UNKNOWN"}) {
            mvc.perform(post("/api/v1/admin/jobs").header("Authorization", "Bearer " + role))
                .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + role))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void staffCanOperateAssignedEndpointsWithoutAccessingManagerOrAdminRoutes() throws Exception {
        for (String path : new String[]{
            "/api/v1/staff/dashboard",
            "/api/v1/staff/rating-appeals",
            "/api/v1/admin/jobs",
            "/api/v1/admin/resume-templates"}) {
            mvc.perform(get(path).header("Authorization", "Bearer STAFF")).andExpect(status().isOk());
        }
        for (String path : new String[]{
            "/api/v1/manager/dashboard",
            "/api/v1/admin/dashboard",
            "/api/v1/admin/users",
            "/api/v1/admin/ai/configurations"}) {
            mvc.perform(get(path).header("Authorization", "Bearer STAFF")).andExpect(status().isForbidden());
        }
    }

    @Test
    void retiredInternalWorkEndpointsRemainDeniedForEverySupportedRole() throws Exception {
        for (String role : new String[]{"ADMIN", "MANAGER", "STAFF", "CANDIDATE", "CAREER_CONSULTANT"}) {
            for (String path : new String[]{"/api/v1/management/tasks", "/api/v1/admin/management/overview"}) {
                mvc.perform(get(path).header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void adviceAndPrivateNotesUseDistinctReadAndWriteRoles() throws Exception {
        String advice = "/api/v1/consultations/x/advice";
        String notes = "/api/v1/consultations/x/private-notes";
        mvc.perform(get(advice).header("Authorization", "Bearer CANDIDATE")).andExpect(status().isOk());
        mvc.perform(put(advice).header("Authorization", "Bearer CANDIDATE")).andExpect(status().isForbidden());
        mvc.perform(get(notes).header("Authorization", "Bearer CANDIDATE")).andExpect(status().isForbidden());
        mvc.perform(put(advice).header("Authorization", "Bearer CAREER_CONSULTANT")).andExpect(status().isOk());
        mvc.perform(put(notes).header("Authorization", "Bearer CAREER_CONSULTANT")).andExpect(status().isOk());
        mvc.perform(get(notes).header("Authorization", "Bearer MANAGER")).andExpect(status().isForbidden());
    }

    @Test
    void candidateAndConsultantCannotReadManagerReports() throws Exception {
        for (String role : new String[]{"CANDIDATE", "CAREER_CONSULTANT"}) {
            mvc.perform(get("/api/v1/manager/payments").header("Authorization", "Bearer " + role))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/manager/payments")).andExpect(status().isUnauthorized());
    }
}
