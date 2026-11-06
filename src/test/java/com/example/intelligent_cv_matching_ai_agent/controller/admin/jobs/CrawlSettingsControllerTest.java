package com.example.intelligent_cv_matching_ai_agent.controller.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.SecurityConfig;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiExceptionHandler;
import com.example.intelligent_cv_matching_ai_agent.security.shared.BearerFilter;
import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.CrawlSettingsService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.jobs.JobCrawlService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CrawlSettingsControllerTest {

    private static final String SOURCE = """
        {"sourceSite":"ITVIEC","keyword":"Java","roleCategory":"Backend",
        "location":"Hà Nội","limit":7,"maxPages":2}
        """;

    private static final String VALID_REQUEST =
        "{\"enabled\":true,\"fixedDelay\":\"PT1M\",\"sources\":[" + SOURCE + "]}";

    private AnnotationConfigWebApplicationContext context;

    private MockMvc mvc;

    private CrawlSettingsService settings;

    private JobCrawlService crawler;

    private UUID actor;

    @BeforeEach
    void createOnlyTheMvcAndSecurityContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        settings = context.getBean(CrawlSettingsService.class);
        crawler = context.getBean(JobCrawlService.class);
        actor = UUID.randomUUID();
        TokenService tokens = context.getBean(TokenService.class);
        when(tokens.authenticate("admin")).thenReturn(Map.of("id", actor, "role", "ADMIN"));
        when(tokens.authenticate("candidate")).thenReturn(Map.of("id", UUID.randomUUID(), "role", "CANDIDATE"));
    }

    @AfterEach
    void closeTheIsolatedContext() {
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/v1/admin"})
    void administratorCanReadSettingsThroughEitherApiPrefix(
        String prefix)
        throws Exception {
        when(settings.get()).thenReturn(response());

        mvc.perform(get(prefix + "/crawl-settings").header("Authorization", "Bearer admin")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.fixedDelay").value("PT1M"))
            .andExpect(jsonPath("$.configurationSource").value("DATABASE")).andExpect(jsonPath("$.version").value(4))
            .andExpect(jsonPath("$.actorId").value(actor.toString()))
            .andExpect(jsonPath("$.sources[0].location").value("Ha Noi"));

        verify(settings).get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/v1/admin"})
    void administratorCanSaveSettingsWithTheActorOmitted(
        String prefix)
        throws Exception {
        when(settings.update(any())).thenReturn(response());

        mvc.perform(
            put(prefix + "/crawl-settings").header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)
        ).andExpect(status().isOk()).andExpect(jsonPath("$.configurationSource").value("DATABASE"));

        ArgumentCaptor<CrawlSettingsRequest> request = ArgumentCaptor.forClass(CrawlSettingsRequest.class);
        verify(settings).update(request.capture());
        assertTrue(request.getValue().enabled());
        assertEquals("PT1M", request.getValue().fixedDelay());
        assertNull(request.getValue().actorId());
        assertEquals("Hà Nội", request.getValue().sources().getFirst().location());
        assertEquals(7, request.getValue().sources().getFirst().limit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/v1/admin"})
    void historyUsesTheExistingRunRecordsAndRequestedPagination(
        String prefix)
        throws Exception {
        UUID run = UUID.randomUUID();
        when(crawler.runs(7, 2)).thenReturn(List.of(Map.of("id", run, "status", "PARTIAL", "wrong_region_pages", 1)));

        mvc.perform(
            get(prefix + "/crawl-history").header("Authorization", "Bearer admin").param("limit", "7")
                .param("offset", "2")
        ).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(run.toString()))
            .andExpect(jsonPath("$[0].wrong_region_pages").value(1));

        verify(crawler).runs(7, 2);
        verifyNoInteractions(settings);
    }

    @Test
    void historyDefaultsToTwentyRunsStartingAtZero() throws Exception {
        mvc.perform(get("/api/admin/crawl-history").header("Authorization", "Bearer admin")).andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        verify(crawler).runs(20, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/v1/admin"})
    void anonymousCallersCannotReadSaveOrReadHistory(
        String prefix)
        throws Exception {
        mvc.perform(get(prefix + "/crawl-settings")).andExpect(status().isUnauthorized());
        mvc.perform(put(prefix + "/crawl-settings").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(prefix + "/crawl-history")).andExpect(status().isUnauthorized());

        verifyNoInteractions(settings, crawler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin", "/api/v1/admin"})
    void authenticatedCandidatesCannotReadSaveOrReadHistory(
        String prefix)
        throws Exception {
        mvc.perform(get(prefix + "/crawl-settings").header("Authorization", "Bearer candidate"))
            .andExpect(status().isForbidden());
        mvc.perform(
            put(prefix + "/crawl-settings").header("Authorization", "Bearer candidate")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)
        ).andExpect(status().isForbidden());
        mvc.perform(get(prefix + "/crawl-history").header("Authorization", "Bearer candidate"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(settings, crawler);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidBodiesAreRejectedBeforeCallingTheSettingsService(
        String request)
        throws Exception {
        mvc.perform(
            put("/api/admin/crawl-settings").header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON).content(request)
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(settings, crawler);
    }

    private static Stream<String> invalidRequests() {
        return Stream.of(
            "{\"fixedDelay\":\"PT1M\",\"sources\":[]}",
            "{\"enabled\":false,\"sources\":[]}",
            "{\"enabled\":false,\"fixedDelay\":\"PT1M\"}",
            VALID_REQUEST.replace("ITVIEC", "OTHER"),
            VALID_REQUEST.replace("\"Java\"", "\" \""),
            VALID_REQUEST.replace("\"limit\":7", "\"limit\":101"),
            VALID_REQUEST.replace("\"maxPages\":2", "\"maxPages\":11")
        );
    }

    private CrawlSettingsResponse response() {
        return new CrawlSettingsResponse(
            true,
            "PT1M",
            actor,
            List.of(new CrawlSourceSettingsResponse("ITVIEC", "Java", "Backend", "Ha Noi", 7, 2)),
            "DATABASE",
            4,
            actor,
            Instant.parse("2026-10-01T00:00:00Z")
        );
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, BearerFilter.class, CrawlSettingsController.class, ApiExceptionHandler.class})
    static class MvcConfiguration {

        @Bean
        TokenService tokens() {
            return mock(TokenService.class);
        }

        @Bean
        CrawlSettingsService settings() {
            return mock(CrawlSettingsService.class);
        }

        @Bean
        JobCrawlService crawler() {
            return mock(JobCrawlService.class);
        }
    }
}
