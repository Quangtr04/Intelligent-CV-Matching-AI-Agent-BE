package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
class ResumeTemplateSelectionIntegrationTests {

    // Template/section integration is independent of the machine's Chromium installation.
    // Real PDF storage/transactions are exercised by ResumePdfPersistenceTests.
    @MockitoBean
    private ResumePdfExportService pdfExports;

    private static final String BASE = "/api/v1/resumes";

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

    private UUID candidateId;

    private String candidate;

    private String other;

    @BeforeEach
    void identities() {
        reset(ai);
        candidateId = user();
        candidate = token(candidateId);
        other = token(user());
    }

    @Test
    void manualAndGeneratedResumesPersistTheSelectedActiveTemplateAndExposeItsHtml() throws Exception {
        UUID template = template("Selected layout");
        var manual = create(template);
        assertEquals(template.toString(), manual.get("template_id"));
        assertEquals("Selected layout", ((Map<?, ?>) manual.get("template")).get("name"));
        mvc.perform(get(BASE + "/" + manual.get("id")).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.template.id").value(template.toString()))
            .andExpect(jsonPath("$.template.html_content").value(templateHtml()))
            .andExpect(jsonPath("$.template.layout_config").doesNotExist());
        verifyNoInteractions(ai);

        when(ai.generate(eq("CV_BUILD"), anyMap())).thenReturn(Map.of("summary", "Verified builder facts"));
        mvc.perform(
            post(BASE + "/build").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input(template)))
        ).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("AI_BUILDER"))
            .andExpect(jsonPath("$.template_id").value(template.toString()))
            .andExpect(jsonPath("$.template.name").value("Selected layout"));
        verify(ai).generate(eq("CV_BUILD"), argThat(body -> template.toString().equals(body.get("template_id"))));
    }

    @Test
    void disabledTemplateRemainsReadableForTheOwnedResumeButCannotBeNewlySelected() throws Exception {
        UUID template = template("Historical layout");
        String id = create(template).get("id").toString();
        db.update("UPDATE resume_templates SET is_active=false WHERE id=?", template);
        mvc.perform(get(BASE + "/" + id).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.template_id").value(template.toString()))
            .andExpect(jsonPath("$.template.is_active").value(false))
            .andExpect(jsonPath("$.template.html_content").value(templateHtml()));
        List<?> available = mapper.readValue(
            mvc.perform(get(BASE + "/templates")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(),
            List.class
        );
        assertFalse(available.stream().anyMatch(row -> template.toString().equals(((Map<?, ?>) row).get("id"))));
        mvc.perform(
            post(BASE).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input(template)))
        ).andExpect(status().isNotFound());
        select(id, "{\"template_id\":\"" + template + "\"}").andExpect(status().isNotFound());

        select(id, "{\"title\":\"Updated content retains inactive selection\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.template_id").value(template.toString()))
            .andExpect(jsonPath("$.template.is_active").value(false));
        assertEquals(template, db.scalar("SELECT template_id FROM resumes WHERE id=?", UUID.fromString(id)));
    }

    @Test
    void creatingWithoutATemplateAndOmittingItOnEditRetainsTheOptionalContract() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", "CV without a selected layout");
        input.put("structured_data", Map.of("summary", "Verified experience"));
        Map<String, Object> created = response(
            mvc.perform(
                post(BASE).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(input))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.template_id").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.template").value(org.hamcrest.Matchers.nullValue())).andReturn()
        );
        select(created.get("id").toString(), "{\"title\":\"Updated CV without a selected layout\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.template_id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.template").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(ai);
    }

    @Test
    void missingAndDisabledTemplatesAreRejectedBeforeManualOrAiCreation() throws Exception {
        UUID disabled = template("Disabled layout");
        db.update("UPDATE resume_templates SET is_active=false WHERE id=?", disabled);
        for (UUID template : List.of(disabled, UUID.randomUUID())) {
            for (String path : List.of(BASE, BASE + "/build")) {
                mvc.perform(
                    post(path).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input(template)))
                ).andExpect(status().isNotFound());
            }
        }
        assertEquals(0L, db.scalar("SELECT count(*) FROM resumes WHERE user_id=?", candidateId));
        verifyNoInteractions(ai);
    }

    @Test
    void selectingAndClearingOnlyTheTemplatePreservesConfirmedContentVectorsAndRoadmap() throws Exception {
        UUID first = template("First layout"), second = template("Second layout");
        UUID id = UUID.fromString(create(first).get("id").toString());
        mvc.perform(
            post(BASE + "/" + id + "/confirm").header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON).content("{\"is_primary\":true}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.is_primary").value(true));
        Map<String, Object> before = db.one("SELECT * FROM resumes WHERE id=?", id);
        double[] vector = new double[1024];
        vector[0] = 1;
        UUID embedding = (UUID) db.scalar(
            "INSERT INTO resume_embeddings(resume_id,provider,model,embedding,content_hash,is_current) "
                + "VALUES (?,'test','template-preservation',?::vector,?,true) RETURNING id",
            id,
            Arrays.toString(vector),
            before.get("content_hash")
        );
        UUID gap = db.insert(
            "gap_analyses",
            Map.of("user_id", candidateId, "resume_id", id, "input_source", "CV", "status", "COMPLETED")
        );
        UUID roadmap = db.insert(
            "roadmaps",
            Map.of("user_id", candidateId, "gap_analysis_id", gap, "title", "Keep this roadmap current")
        );
        UUID analysis = db.insert(
            "resume_analyses",
            Map.of("resume_id", id, "analysis_type", "AI_QUALITY", "summary", "Keep the existing analysis")
        );
        UUID suggestion = db.insert(
            "resume_analysis_suggestions",
            Map.of("analysis_id", analysis, "explanation", "Keep the reviewed suggestion", "accepted", true)
        );
        Map<String, Object> analysisBefore = db.one("SELECT * FROM resume_analyses WHERE id=?", analysis);
        Map<String, Object> suggestionBefore =
            db.one("SELECT * FROM resume_analysis_suggestions WHERE id=?", suggestion);

        select(id.toString(), "{\"template_id\":\"" + second + "\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.template_id").value(second.toString()))
            .andExpect(jsonPath("$.template.name").value("Second layout"));
        assertContentUnchanged(before, id, embedding, roadmap);
        assertEquals(analysisBefore, db.one("SELECT * FROM resume_analyses WHERE id=?", analysis));
        assertEquals(suggestionBefore, db.one("SELECT * FROM resume_analysis_suggestions WHERE id=?", suggestion));
        select(id.toString(), "{\"template_id\":null}").andExpect(status().isOk())
            .andExpect(jsonPath("$.template_id").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.template").value(org.hamcrest.Matchers.nullValue()));
        assertContentUnchanged(before, id, embedding, roadmap);
        assertEquals(analysisBefore, db.one("SELECT * FROM resume_analyses WHERE id=?", analysis));
        assertEquals(suggestionBefore, db.one("SELECT * FROM resume_analysis_suggestions WHERE id=?", suggestion));
        assertNull(db.scalar("SELECT template_id FROM resumes WHERE id=?", id));
        verifyNoInteractions(ai);

        select(id.toString(), "{\"title\":\"Changed CV facts\",\"template_id\":\"" + first + "\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.template_id").value(first.toString()))
            .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED")).andExpect(jsonPath("$.is_primary").value(false));
        assertNotEquals(before.get("content_hash"), db.scalar("SELECT content_hash FROM resumes WHERE id=?", id));
        assertEquals(false, db.scalar("SELECT is_current FROM resume_embeddings WHERE id=?", embedding));
        assertEquals(true, db.scalar("SELECT is_outdated FROM roadmaps WHERE id=?", roadmap));
    }

    @Test
    void selectionEnforcesAuthenticationOwnershipArchiveAndMissingTemplateChecks() throws Exception {
        UUID first = template("Original layout"), second = template("Other layout");
        String id = create(first).get("id").toString();
        String patchBody = "{\"template_id\":\"" + second + "\"}";
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(patch(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(patchBody))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(
            patch(BASE + "/" + id).header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        ).andExpect(status().isNotFound());
        select(id, "{\"template_id\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        assertEquals(first, db.scalar("SELECT template_id FROM resumes WHERE id=?", UUID.fromString(id)));
        mvc.perform(delete(BASE + "/" + id).header("Authorization", candidate)).andExpect(status().isNoContent());
        select(id, patchBody).andExpect(status().isConflict());
        assertEquals(first, db.scalar("SELECT template_id FROM resumes WHERE id=?", UUID.fromString(id)));
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @MethodSource("invalidTemplateIds")
    void malformedTemplateSelectionIsRejectedBeforeAnyWrite(
        String value)
        throws Exception {
        UUID template = template("Original layout");
        String id = create(template).get("id").toString();
        select(id, "{\"template_id\":" + value + "}").andExpect(status().isBadRequest());
        assertEquals(template, db.scalar("SELECT template_id FROM resumes WHERE id=?", UUID.fromString(id)));
        verifyNoInteractions(ai);
    }

    private static Stream<String> invalidTemplateIds() {
        return Stream.of("\"invalid\"", "\"\"", "42", "true", "{}", "[]", "\"1-1-1-1-1\"");
    }

    private void assertContentUnchanged(
        Map<String, Object> before,
        UUID id,
        UUID embedding,
        UUID roadmap) {
        Map<String, Object> after = db.one("SELECT * FROM resumes WHERE id=?", id);
        for (String field : List
            .of("content_hash", "structured_data", "raw_content", "title", "language", "status", "is_primary")) {
            assertEquals(before.get(field), after.get(field), field);
        }
        assertEquals(true, db.scalar("SELECT is_current FROM resume_embeddings WHERE id=?", embedding));
        assertEquals(false, db.scalar("SELECT is_outdated FROM roadmaps WHERE id=?", roadmap));
    }

    private org.springframework.test.web.servlet.ResultActions select(
        String id,
        String body)
        throws Exception {
        return mvc.perform(
            patch(BASE + "/" + id).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(body)
        );
    }

    private Map<String, Object> create(
        UUID template)
        throws Exception {
        return response(
            mvc.perform(
                post(BASE).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(input(template)))
            ).andExpect(status().isOk()).andReturn()
        );
    }

    private Map<String, Object> input(
        UUID template) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", "Template selection CV");
        input.put("language", "vi");
        input.put("template_id", template.toString());
        input.put("structured_data", Map.of("summary", "Verified candidate experience"));
        return input;
    }

    private UUID template(
        String name) {
        return db.insert(
            "resume_templates",
            Map.of(
                "name",
                name,
                "language",
                "vi",
                "is_active",
                true,
                "is_trial_enabled",
                true,
                "html_content",
                templateHtml()
            )
        );
    }

    private static String templateHtml() {
        return "<html><head></head><body><h1 data-cv-field=\"fullName\"></h1>"
            + "<section data-cv-section=\"summary\"></section></body></html>";
    }

    private UUID user() {
        return db.insert(
            "users",
            Map.of(
                "email",
                "cv-template-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Template Selection Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "CANDIDATE"
            )
        );
    }

    private String token(
        UUID id) {
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private Map<String, Object> response(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }
}
