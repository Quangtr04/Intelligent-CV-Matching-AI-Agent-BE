package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.LinkedHashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class ResumeTemplateAdminIntegrationTests {

    private static final String BASE = "/api/v1/admin/resume-templates";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JsonMapper mapper;

    private String admin;

    private String candidate;

    @BeforeEach
    void identities() {
        admin = token("ADMIN");
        candidate = token("CANDIDATE");
    }

    @Test
    void migratedSeedTemplatesContainSafeHtmlAndSupportedLanguageCodes() {
        Map<String, Object> english =
            db.one("SELECT * FROM resume_templates WHERE id='30000000-0000-0000-0000-000000000001'");
        Map<String, Object> vietnamese =
            db.one("SELECT * FROM resume_templates WHERE id='30000000-0000-0000-0000-000000000002'");
        assertEquals("en", english.get("language"));
        assertEquals("vi", vietnamese.get("language"));
        assertFalse(english.containsKey("layout_config"));
        assertFalse(vietnamese.containsKey("layout_config"));
        assertNotNull(ResumeTemplateHtml.normalize(english.get("html_content").toString()));
        assertNotNull(ResumeTemplateHtml.normalize(vietnamese.get("html_content").toString()));
        assertTrue(english.get("html_content").toString().contains("#1E3A8A"));
        assertTrue(vietnamese.get("html_content").toString().contains("grid-template-columns:30% 70%"));
        assertTrue(vietnamese.get("html_content").toString().contains("Giới thiệu"));
    }

    @Test
    void htmlTemplatesRoundTripThroughAdministrationPublicCandidateAndGuestViews() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(request());
        body.put("htmlContent", html("First HTML"));
        Map<String, Object> created = response(
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))
            ).andExpect(status().isOk()).andReturn()
        );
        String id = created.get("id").toString();
        String normalized = created.get("html_content").toString();
        assertTrue(normalized.contains("First HTML"));
        assertFalse(created.containsKey("layout_config"));
        assertEquals(
            normalized,
            db.scalar("SELECT html_content FROM resume_templates WHERE id=?", UUID.fromString(id))
        );
        mvc.perform(get(BASE + "/" + id).header("Authorization", admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.html_content").value(normalized));
        assertListedHtml("/api/v1/resumes/templates", null, null, id, normalized);
        assertListedHtml("/api/v1/resumes/templates", "Authorization", candidate, id, normalized);

        String guestKey = TokenService.random("g_");
        db.insert(
            "guest_sessions",
            Map.of(
                "session_key",
                TokenService.hash(guestKey),
                "token_limit",
                5000,
                "token_used",
                0,
                "expires_at",
                OffsetDateTime.now().plusHours(1)
            )
        );
        assertListedHtml("/api/v1/guest/templates", "X-Guest-Session", guestKey, id, normalized);

        var cvBody = Map.of(
            "title",
            "HTML CV",
            "language",
            "vi",
            "template_id",
            id,
            "structured_data",
            Map.of("summary", "Candidate facts remain separate from template markup")
        );
        Map<String, Object> cv = response(
            mvc.perform(
                post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(cvBody))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.template.html_content").value(normalized)).andReturn()
        );
        mvc.perform(
            patch(BASE + "/" + id + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")
        ).andExpect(status().isOk());
        assertFalse(publicTemplatesContain(id));
        mvc.perform(get("/api/v1/resumes/" + cv.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.template.html_content").value(normalized))
            .andExpect(jsonPath("$.template.is_active").value(false));
        mvc.perform(
            post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cvBody))
        ).andExpect(status().isNotFound());
    }

    @Test
    void fullUpdateReplacesHtmlAndRejectsRemovingItsRequiredDocument() throws Exception {
        var body = new LinkedHashMap<>(request());
        body.put("htmlContent", html("Original"));
        var created = response(
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))
            ).andExpect(status().isOk()).andReturn()
        );
        String id = created.get("id").toString();
        body.put("htmlContent", html("Replacement"));
        mvc.perform(
            put(BASE + "/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.html_content", org.hamcrest.Matchers.containsString("Replacement")));
        body.remove("htmlContent");
        mvc.perform(
            put(BASE + "/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        ).andExpect(status().isBadRequest());
        assertTrue(
            db.scalar("SELECT html_content FROM resume_templates WHERE id=?", UUID.fromString(id)).toString()
                .contains("Replacement")
        );
    }

    @Test
    void invalidOrMissingHtmlFailsBeforePersistence() throws Exception {
        long before = ((Number) db.scalar("SELECT count(*) FROM resume_templates")).longValue();
        for (String invalidHtml : List
            .of(html("x") + "<script>alert(1)</script>", html("x").replace("summary", "unknown"))) {
            var body = new LinkedHashMap<>(request());
            body.put("htmlContent", invalidHtml);
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(body))
            ).andExpect(status().isBadRequest());
        }
        var noLayout = new LinkedHashMap<>(request());
        noLayout.put("htmlContent", "  ");
        mvc.perform(
            post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(noLayout))
        ).andExpect(status().isBadRequest());
        assertEquals(before, ((Number) db.scalar("SELECT count(*) FROM resume_templates")).longValue());
    }

    @ParameterizedTest
    @MethodSource("invalidStoredHtmlLengths")
    void databaseEnforcesBothHtmlLengthBounds(
        String html) {
        assertThrows(
            DataAccessException.class,
            () -> db.update(
                "UPDATE resume_templates SET html_content=? WHERE id='30000000-0000-0000-0000-000000000001'",
                html
            )
        );
    }

    private static Stream<String> invalidStoredHtmlLengths() {
        return Stream.of(null, "", " \t\r\n ", "x".repeat(200001));
    }

    private static String html(
        String heading) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>@page{size:A4;margin:0}"
            + "body{display:grid;grid-template-columns:69% 31%}</style></head><body>"
            + "<h1 data-cv-field=\"fullName\">" + heading + "</h1>"
            + "<section data-cv-section=\"summary\"></section></body></html>";
    }

    private void assertListedHtml(
        String url,
        String header,
        String value,
        String id,
        String html)
        throws Exception {
        var request = get(url);
        if (header != null) {
            request.header(header, value);
        }
        String json = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<?> templates = mapper.readValue(json, List.class);
        Map<?, ?> template = (Map<?, ?>) templates.stream().filter(item -> id.equals(((Map<?, ?>) item).get("id")))
            .findFirst().orElseThrow();
        assertEquals(html, template.get("html_content"));
    }

    @Test
    void createEditDisableAndReactivatePreserveExistingResumesAndAuditTheChanges() throws Exception {
        var created = response(
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request()))
            ).andExpect(status().isOk()).andReturn()
        );
        String id = created.get("id").toString();
        assertEquals("Admin Template", created.get("name"));
        assertTrue(created.get("html_content").toString().contains("Admin HTML"));
        assertFalse(created.containsKey("layout_config"));
        Map<String, Object> cvInput = Map.of(
            "title",
            "Historical template reference",
            "language",
            "vi",
            "template_id",
            id,
            "structured_data",
            Map.of("summary", "A test CV with a retained template reference")
        );
        var cv = response(
            mvc.perform(
                post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(cvInput))
            ).andExpect(status().isOk()).andReturn()
        );
        Map<String, Object> edited = new LinkedHashMap<>(request());
        edited.put("name", "Updated Template");
        edited.remove("careerPath");
        edited.remove("description");
        edited.put("htmlContent", html("Updated HTML"));
        mvc.perform(
            put(BASE + "/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(edited))
        ).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated Template"))
            .andExpect(jsonPath("$.description").value(""))
            .andExpect(jsonPath("$.html_content", org.hamcrest.Matchers.containsString("Updated HTML")));
        assertNull(db.scalar("SELECT career_path FROM resume_templates WHERE id=?", UUID.fromString(id)));

        mvc.perform(
            patch(BASE + "/" + id + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.is_active").value(false))
            .andExpect(jsonPath("$.is_trial_enabled").value(true));
        assertFalse(publicTemplatesContain(id));
        mvc.perform(get(BASE + "/" + id).header("Authorization", admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.is_active").value(false));
        mvc.perform(get("/api/v1/resumes/" + cv.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.template_id").value(id));
        mvc.perform(
            post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cvInput))
        ).andExpect(status().isNotFound());

        mvc.perform(
            patch(BASE + "/" + id + "/status").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.html_content", org.hamcrest.Matchers.containsString("Updated HTML")));
        assertTrue(publicTemplatesContain(id));
        mvc.perform(
            post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cvInput))
        ).andExpect(status().isOk());
        assertEquals(
            4,
            ((Number) db.scalar("SELECT count(*) FROM audit_logs WHERE entity_id=?", UUID.fromString(id))).intValue()
        );
        Map<String, Object> audit = db.one(
            "SELECT new_value FROM audit_logs WHERE entity_id=? AND action='UPDATE_RESUME_TEMPLATE'",
            UUID.fromString(id)
        );
        Map<?, ?> newValue = (Map<?, ?>) audit.get("new_value");
        assertEquals("Updated Template", newValue.get("name"));
        assertFalse(newValue.containsKey("layout_config"));
        assertTrue(newValue.get("html_content").toString().contains("Updated HTML"));
    }

    @Test
    void everyAdministratorRouteRejectsAnonymousAndCandidateUsers() throws Exception {
        String id = UUID.randomUUID().toString();
        String body = mapper.writeValueAsString(request());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(
            patch(BASE + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")
        ).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).header("Authorization", candidate)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id).header("Authorization", candidate)).andExpect(status().isForbidden());
        mvc.perform(post(BASE).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(
            put(BASE + "/" + id).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isForbidden());
        mvc.perform(
            patch(BASE + "/" + id + "/status").header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")
        ).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void malformedBodiesAreRejectedWithoutCreatingRows(
        String body)
        throws Exception {
        long before = ((Number) db.scalar("SELECT count(*) FROM resume_templates")).longValue();
        mvc.perform(post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        assertEquals(before, ((Number) db.scalar("SELECT count(*) FROM resume_templates")).longValue());
    }

    private static Stream<String> invalidBodies() {
        return Stream.of(
            invalidBody("name", " "),
            invalidBody("name", "Bad\0Name"),
            invalidBody("language", "EN"),
            invalidBody("active", "true"),
            invalidBody("trialEnabled", null),
            invalidBody("htmlContent", null),
            invalidBody("htmlContent", " "),
            invalidBody("htmlContent", "x".repeat(200001)),
            invalidBody("htmlContent", html("Unsafe") + "<script>alert(1)</script>"),
            invalidBody("htmlContent", "<p>Missing CV slots</p>")
        );
    }

    private static String invalidBody(
        String field,
        Object value) {
        var body = new LinkedHashMap<>(request());
        body.put(field, value);
        return JsonMapper.builder().build().writeValueAsString(body);
    }

    @Test
    void boundedPaginationAndMissingIdentifiersHaveExplicitErrors() throws Exception {
        mvc.perform(get(BASE).header("Authorization", admin).param("limit", "1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
        for (String limit : List.of("0", "101")) {
            mvc.perform(get(BASE).header("Authorization", admin).param("limit", limit))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).header("Authorization", admin).param("offset", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).header("Authorization", admin).param("offset", "100001"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).header("Authorization", admin).param("offset", "100000")).andExpect(status().isOk());
        String missing = UUID.randomUUID().toString();
        mvc.perform(get(BASE + "/" + missing).header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(
            put(BASE + "/" + missing).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request()))
        ).andExpect(status().isNotFound());
        mvc.perform(
            patch(BASE + "/" + missing + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")
        ).andExpect(status().isNotFound());
        mvc.perform(
            patch(BASE + "/" + missing + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":\"false\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void databaseStoresHtmlAsTheOnlyRequiredLayoutRepresentation() {
        assertEquals(
            0,
            ((Number) db.scalar(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() "
                    + "AND table_name='resume_templates' AND column_name='layout_config'"
            )).intValue()
        );
        assertEquals(
            "NO",
            db.scalar(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema=current_schema() "
                    + "AND table_name='resume_templates' AND column_name='html_content'"
            )
        );
    }

    private String token(
        String role) {
        UUID id = db.insert(
            "users",
            Map.of(
                "email",
                "template-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Template Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private Map<String, Object> response(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private boolean publicTemplatesContain(
        String id)
        throws Exception {
        String json = mvc.perform(get("/api/v1/resumes/templates")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        List<?> templates = mapper.readValue(json, List.class);
        return templates.stream().anyMatch(value -> id.equals(((Map<?, ?>) value).get("id")));
    }

    private static Map<String, Object> request() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "  Admin Template  ");
        body.put("careerPath", " Software Engineer ");
        body.put("language", "vi");
        body.put("description", " A safe test layout ");
        body.put("active", true);
        body.put("trialEnabled", true);
        body.put("htmlContent", html("Admin HTML"));
        return body;
    }
}
