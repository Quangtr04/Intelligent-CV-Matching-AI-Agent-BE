package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AssessmentPreparationIntegrationTests {

    private static final String PATH = "/api/v1/assessments/preparation";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JsonMapper mapper;

    @MockitoBean
    private AiService ai;

    private UUID owner;

    private String authorization;

    @BeforeEach
    void identity() {
        reset(ai);
        owner = identity("CANDIDATE");
        authorization = bearer(owner);
    }

    @Test
    void onlyAuthenticatedCandidatesCanPrepare() throws Exception {
        UUID job = job();
        mvc.perform(get(PATH).param("jobId", job.toString())).andExpect(status().isUnauthorized());
        for (String role : List.of("ADMIN", "CAREER_CONSULTANT")) {
            mvc.perform(get(PATH).param("jobId", job.toString()).header("Authorization", bearer(identity(role))))
                .andExpect(status().isForbidden());
        }
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void missingMalformedAndNonexistentJobsAreRejected() throws Exception {
        mvc.perform(get(PATH).header("Authorization", authorization)).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("jobId", "not-a-uuid").header("Authorization", authorization))
            .andExpect(status().isBadRequest());
        read(UUID.randomUUID(), 404);
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "REMOVED", "INVALID", "expired-date"})
    void inactiveOrExpiredJobsCannotBePresentedAsReady(
        String state)
        throws Exception {
        UUID job = job();
        if ("expired-date".equals(state)) {
            db.update(
                "UPDATE market_job_posts SET valid_through=? WHERE id=?",
                OffsetDateTime.now().minusHours(1),
                job
            );
        } else {
            db.update("UPDATE market_job_posts SET status=? WHERE id=?", state, job);
        }
        read(job, 404);
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "skills", "blank-skills"})
    void unusableJobDataIsAnActionableConflict(
        String missing)
        throws Exception {
        UUID job = job();
        switch (missing) {
            case "title" -> db.update("UPDATE market_job_posts SET job_title='  ' WHERE id=?", job);
            case "description" -> db.update("UPDATE market_job_posts SET raw_content='  ' WHERE id=?", job);
            case "skills" -> db.update("DELETE FROM market_job_skills WHERE market_job_id=?", job);
            case "blank-skills" -> db.update("UPDATE market_job_skills SET skill_name='  ' WHERE market_job_id=?", job);
            default -> throw new AssertionError(missing);
        }
        read(job, 409);
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void returnsEachCanonicalJdSkillWithRelevantTrustedResourcesWithoutGeneratingOrSavingAQuiz() throws Exception {
        UUID job = job();
        db.insert(
            "market_job_skills",
            fields(
                "market_job_id",
                job,
                "skill_name",
                "PostgreSQL",
                "normalized_skill",
                "postgresql",
                "is_required",
                false
            )
        );
        String javaUrl = "https://docs.example.test/preparation/java/" + UUID.randomUUID();
        UUID javaResource = resource("Java", "java", javaUrl, true);
        String unrelatedUrl = "https://docs.example.test/preparation/react/" + UUID.randomUUID();
        resource("React", "react", unrelatedUrl, true);
        String inactiveUrl = "https://docs.example.test/preparation/inactive/" + UUID.randomUUID();
        resource("Java", "java", inactiveUrl, false);
        resource("Java", "java", "javascript:alert('" + UUID.randomUUID() + "')", true);
        resource("Java", "java", "https://user:password@docs.example.test/" + UUID.randomUUID(), true);

        Map<String, Object> response = read(job, 200);
        assertEquals(job.toString(), response.get("job_id"));
        assertEquals("Java Backend Engineer", response.get("job_title"));
        List<?> skills = (List<?>) response.get("skills");
        assertEquals(2, skills.size());
        Map<?, ?> java = (Map<?, ?>) skills.getFirst();
        assertEquals("Java", java.get("skill_name"));
        assertEquals(true, java.get("is_required"));
        List<?> resources = (List<?>) java.get("resources");
        assertTrue(
            resources.stream().map(row -> (Map<?, ?>) row)
                .anyMatch(row -> javaResource.toString().equals(row.get("id")) && javaUrl.equals(row.get("url")))
        );
        assertEquals("PostgreSQL", ((Map<?, ?>) skills.getLast()).get("skill_name"));
        assertEquals(false, ((Map<?, ?>) skills.getLast()).get("is_required"));
        String json = mapper.writeValueAsString(response);
        assertFalse(json.contains(unrelatedUrl));
        assertFalse(json.contains(inactiveUrl));
        assertFalse(json.contains("javascript:"));
        assertFalse(json.contains("user:password"));
        assertFalse(response.containsKey("roadmaps"));
        assertFalse(response.containsKey("questions"));
        assertFalse(json.contains("correct_option"));
        assertFalse(json.contains("explanation"));
        assertEquals(
            0L,
            ((Number) db.scalar("SELECT count(*) FROM assessment_sessions WHERE user_id=?", owner)).longValue()
        );
        assertEquals(0L, ((Number) db.scalar("SELECT count(*) FROM roadmaps WHERE user_id=?", owner)).longValue());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    @Test
    void parsedDescriptionIsEnoughAndUnknownSkillResourcesStayEmpty() throws Exception {
        UUID job = job();
        db.update(
            "UPDATE market_job_posts SET raw_content='',parsed_requirements=? WHERE id=?",
            "Use CustomSkill " + job,
            job
        );
        db.update(
            "UPDATE market_job_skills SET skill_name=?,normalized_skill=? WHERE market_job_id=?",
            "CustomSkill " + job,
            "customskill-" + job,
            job
        );
        Map<String, Object> response = read(job, 200);
        Map<?, ?> skill = (Map<?, ?>) ((List<?>) response.get("skills")).getFirst();
        assertEquals("CustomSkill " + job, skill.get("skill_name"));
        assertEquals(List.of(), skill.get("resources"));
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(), anyString(), anyMap());
    }

    private UUID identity(
        String role) {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@preparation.test",
                "full_name",
                "Preparation Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
    }

    private String bearer(
        UUID userId) {
        return "Bearer " + tokens.issue(userId).get("accessToken");
    }

    private UUID job() {
        UUID id = db.insert(
            "market_job_posts",
            fields(
                "source_site",
                "ITVIEC",
                "raw_content_hash",
                UUID.randomUUID().toString(),
                "job_title",
                "Java Backend Engineer",
                "role_category",
                "Backend",
                "raw_content",
                "Implement Java backend services and PostgreSQL queries."
            )
        );
        db.insert(
            "market_job_skills",
            fields("market_job_id", id, "skill_name", "Java", "normalized_skill", "java", "is_required", true)
        );
        return id;
    }

    private UUID resource(
        String name,
        String slug,
        String url,
        boolean active) {
        return db.insert(
            "learning_resources",
            fields(
                "title",
                name + " preparation",
                "resource_type",
                "ARTICLE",
                "provider",
                "Fixture",
                "url",
                url,
                "source_type",
                "WEB",
                "is_active",
                active,
                "metadata_json",
                db.json(Map.of("skill_slugs", List.of(slug)))
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(
        UUID jobId,
        int expectedStatus)
        throws Exception {
        String response = mvc.perform(get(PATH).param("jobId", jobId.toString()).header("Authorization", authorization))
            .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return mapper.readValue(response, Map.class);
    }
}
