package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises curated course approval and recommendation history on an explicitly supplied test database. */
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "spring.datasource.url=${EDUNEXUS_TEST_DATABASE_URL}",
        "spring.datasource.username=${EDUNEXUS_TEST_DATABASE_USER:edunexus_test}",
        "spring.datasource.password=${EDUNEXUS_TEST_DATABASE_PASSWORD:}",
        "spring.flyway.enabled=${EDUNEXUS_TEST_FLYWAY:false}",
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
        "app.ocr.enabled=false",
        "app.storage.path=target/course-integration-uploads"}
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "EDUNEXUS_TEST_DATABASE_URL", matches = ".+")
class CuratedCourseFlowIntegrationTests {

    private static final String COURSES = "/api/v1/admin/rag/courses";

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

    private String admin;

    private String candidate;

    @BeforeEach
    void identities() {
        reset(ai);
        admin = token("ADMIN");
        candidate = token("CANDIDATE");
    }

    @Test
    void approvedCourseReachesRoadmapAndHistoricalLinksSurviveCatalogChangesAndArchive() throws Exception {
        String skill = "Course Skill " + UUID.randomUUID().toString().substring(0, 8);
        String video = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        String canonicalUrl = "https://www.youtube.com/watch?v=" + video;
        var pending = post(COURSES, admin, course("https://youtu.be/" + video + "?si=tracking", skill));
        String courseId = pending.get("id").toString();
        assertEquals("PENDING_REVIEW", pending.get("status"));
        assertFalse(json(recommendations(skill)).contains(canonicalUrl));

        var approved = post(COURSES + "/" + courseId + "/approve", admin, Map.of());
        assertEquals("APPROVED", approved.get("status"));
        UUID resourceId = UUID.fromString(approved.get("learning_resource_id").toString());
        UUID documentId = UUID.fromString(approved.get("rag_document_id").toString());
        assertEquals(canonicalUrl, approved.get("url"));
        assertTrue(json(recommendations(skill)).contains(canonicalUrl));
        var document = db.one("SELECT * FROM rag_documents WHERE id=?", documentId);
        assertEquals("PENDING", document.get("embedding_status"));
        assertEquals(canonicalUrl, ((Map<?, ?>) document.get("metadata")).get("source_url"));
        assertTrue(
            db.list("SELECT content FROM rag_chunks WHERE document_id=?", documentId).stream()
                .anyMatch(chunk -> chunk.get("content").toString().contains(canonicalUrl))
        );
        var approvedAgain = post(COURSES + "/" + courseId + "/approve", admin, Map.of());
        assertEquals(approved.get("learning_resource_id"), approvedAgain.get("learning_resource_id"));
        assertEquals(approved.get("rag_document_id"), approvedAgain.get("rag_document_id"));

        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "title",
                "Reviewed course roadmap",
                "summary",
                Map.of("overview", "A curated learning plan"),
                "skill_gaps",
                List.of(),
                "nodes",
                List.of(
                    Map.of(
                        "skill_name",
                        skill,
                        "description",
                        "Study the reviewed course",
                        "estimated_hours",
                        12,
                        "prerequisites",
                        List.of()
                    )
                )
            )
        );
        var roadmap = post("/api/v1/roadmaps", candidate, Map.of("targetJobTitle", skill + " Developer"));
        String roadmapId = roadmap.get("id").toString();
        assertTrue(json(roadmap.get("nodes")).contains(canonicalUrl));
        assertTrue(json(roadmap.get("learning_recommendations")).contains(canonicalUrl));

        db.update("UPDATE learning_resources SET url=? WHERE id=?", canonicalUrl + "&catalog_changed=1", resourceId);
        post(COURSES + "/" + courseId + "/archive", admin, Map.of());
        assertEquals(false, db.scalar("SELECT is_active FROM learning_resources WHERE id=?", resourceId));
        assertFalse(json(recommendations(skill)).contains(video));

        var history = read("/api/v1/roadmaps/" + roadmapId, candidate);
        assertEquals(json(roadmap.get("nodes")), json(history.get("nodes")));
        assertEquals(json(roadmap.get("learning_recommendations")), json(history.get("learning_recommendations")));
        assertFalse(json(history).contains("catalog_changed"));
        assertTrue(json(history).contains(canonicalUrl));
        mvc.perform(get("/api/v1/roadmaps/" + roadmapId).header("Authorization", "Bearer " + token("CANDIDATE")))
            .andExpect(status().isNotFound());
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(COURSES + "/" + courseId + "/approve").header("Authorization", "Bearer " + admin)
        ).andExpect(status().isConflict());
    }

    @Test
    void courseManagementRequiresAdminAndRejectsSpoofedOrDuplicateLinks() throws Exception {
        String slug = "fixture-" + UUID.randomUUID();
        Map<String, Object> body = course("https://www.coursera.org/learn/" + slug, "SQL");
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(COURSES)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
        ).andExpect(status().isUnauthorized());
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(COURSES)
                .header("Authorization", "Bearer " + candidate).contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
        ).andExpect(status().isForbidden());
        post(COURSES, admin, body);
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(COURSES)
                .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json(course("https://coursera.org/learn/" + slug + "?utm_source=duplicate", "SQL")))
        ).andExpect(status().isConflict());
        mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(COURSES)
                .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json(course("https://www.coursera.org.evil.example/learn/fake", "SQL")))
        ).andExpect(status().isBadRequest());
    }

    private String token(
        String role) {
        UUID userId = db.insert(
            "users",
            Map.of(
                "email",
                UUID.randomUUID() + "@course-test.invalid",
                "full_name",
                "Course Integration User",
                "password_hash",
                "unused-test-password",
                "status",
                "ACTIVE",
                "role",
                role,
                "auth_provider",
                "EMAIL"
            )
        );
        return tokens.issue(userId).get("accessToken").toString();
    }

    private Map<String, Object> course(
        String url,
        String skill) {
        return Map.of(
            "title",
            "Reviewed " + skill + " course",
            "url",
            url,
            "description",
            "An administrator-reviewed course covering " + skill,
            "skills",
            List.of(skill),
            "language",
            "vi",
            "isFree",
            true
        );
    }

    private Map<String, Object> recommendations(
        String skill)
        throws Exception {
        return response(
            mvc.perform(
                get("/api/v1/roadmaps/recommendations").header("Authorization", "Bearer " + candidate)
                    .param("targetJobTitle", skill + " Developer").param("skills", skill)
            )
        );
    }

    private Map<String, Object> read(
        String path,
        String bearer)
        throws Exception {
        return response(mvc.perform(get(path).header("Authorization", "Bearer " + bearer)));
    }

    private Map<String, Object> post(
        String path,
        String bearer,
        Object body)
        throws Exception {
        return response(
            mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .header("Authorization", "Bearer " + bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body))
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(
        ResultActions result)
        throws Exception {
        return mapper
            .readValue(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class);
    }

    private String json(
        Object value) {
        return mapper.writeValueAsString(value);
    }
}
