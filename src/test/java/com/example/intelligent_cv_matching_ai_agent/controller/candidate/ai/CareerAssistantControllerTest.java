package com.example.intelligent_cv_matching_ai_agent.controller.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.config.shared.SecurityConfig;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiExceptionHandler;
import com.example.intelligent_cv_matching_ai_agent.security.shared.BearerFilter;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.ai.CareerAssistantService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class CareerAssistantControllerTest {

    private final UUID conversation = UUID.randomUUID();

    private final AtomicReference<Map<String, Object>> capturedUpdate = new AtomicReference<>();

    private AnnotationConfigWebApplicationContext context;

    private MockMvc mvc;

    private CareerAssistantService service;

    @BeforeEach
    void createOnlyTheMvcAndSecurityContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcConfiguration.class);
        context.refresh();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        service = context.getBean(CareerAssistantService.class);
        TokenService tokens = context.getBean(TokenService.class);
        when(tokens.authenticate("candidate")).thenReturn(Map.of("id", UUID.randomUUID(), "role", "CANDIDATE"));
        when(service.update(eq(conversation), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(1);
            capturedUpdate.set(new LinkedHashMap<>(input));
            Map<String, Object> response = new LinkedHashMap<>(input);
            response.put("id", conversation);
            return response;
        });
    }

    @AfterEach
    void closeTheIsolatedContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void candidateCanAttachBothResumeAndJobThroughThePatchRoute() throws Exception {
        UUID resume = UUID.randomUUID();
        UUID job = UUID.randomUUID();

        mvc.perform(
            patch(path()).header("Authorization", "Bearer candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"related_resume_id\":\"" + resume + "\",\"related_job_id\":\"" + job + "\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(conversation.toString()))
            .andExpect(jsonPath("$.related_resume_id").value(resume.toString()))
            .andExpect(jsonPath("$.related_job_id").value(job.toString()));

        assertEquals(Map.of("related_resume_id", resume, "related_job_id", job), capturedUpdate.get());
        verify(service).update(eq(conversation), anyMap());
    }

    @Test
    void detachingOneContextKeepsTheOtherContextOmitted() throws Exception {
        mvc.perform(
            patch(path()).header("Authorization", "Bearer candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"related_job_id\":null}")
        ).andExpect(status().isOk());

        Map<String, Object> input = capturedUpdate.get();
        assertNotNull(input);
        assertTrue(input.containsKey("related_job_id"));
        assertNull(input.get("related_job_id"));
        assertFalse(input.containsKey("related_resume_id"));
        assertFalse(input.containsKey("title"));
    }

    @Test
    void candidateCanRenameWithoutChangingAttachedContext() throws Exception {
        mvc.perform(
            patch(path()).header("Authorization", "Bearer candidate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Interview preparation\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Interview preparation"));

        assertEquals(Map.of("title", "Interview preparation"), capturedUpdate.get());
    }

    @Test
    void attachingContextRequiresAuthentication() throws Exception {
        mvc.perform(patch(path()).contentType(MediaType.APPLICATION_JSON).content("{\"related_job_id\":null}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"title\":\" \"}",
            "{\"title\":null}",
            "{\"related_resume_id\":\"invalid-uuid\"}",
            "{\"related_job_id\":\"invalid-uuid\"}"}
    )
    void invalidUpdatesAreRejectedBeforeCallingTheService(
        String body)
        throws Exception {
        mvc.perform(
            patch(path()).header("Authorization", "Bearer candidate").contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    private String path() {
        return "/api/v1/assistant/conversations/" + conversation;
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, BearerFilter.class, CareerAssistantController.class, ApiExceptionHandler.class})
    static class MvcConfiguration {

        @Bean
        TokenService tokens() {
            return mock(TokenService.class);
        }

        @Bean
        CareerAssistantService assistant() {
            return mock(CareerAssistantService.class);
        }
    }
}
