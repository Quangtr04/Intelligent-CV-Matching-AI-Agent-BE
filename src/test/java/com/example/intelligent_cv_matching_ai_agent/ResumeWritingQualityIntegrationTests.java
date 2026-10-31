package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.resume.ResumeWritingTestData;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class ResumeWritingQualityIntegrationTests {

    private static final String DESCRIPTION = "- Built REST APIs.\n- Added automated tests.";

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

    private String candidate;

    private String other;

    @BeforeEach
    void identities() {
        reset(ai);
        owner = user();
        candidate = token(owner);
        other = token(user());
    }

    @Test
    void persistsDerivedWritingScoresAndRereadsTheSameBreakdownWithoutAnotherAiCall() throws Exception {
        UUID resume = resume("READY");
        Map<String, Object> generated = ResumeWritingTestData.report();
        generated.put("content_score", 1);
        generated.put("content_quality", ResumeWritingTestData.content(90, "Reviewed Java experience"));
        generated.put("overall_score", 1);
        generated.put(
            "suggestions",
            List.of(
                Map.of(
                    "category",
                    "WORDING",
                    "severity",
                    "LOW",
                    "original_content",
                    "- Built REST APIs.",
                    "suggested_content",
                    "- Developed REST APIs.",
                    "explanation",
                    "Use a precise action verb without adding achievements.",
                    "_internal",
                    "PRIVATE_MODEL_TRACE"
                )
            )
        );
        int[] scores = {40, 60, 80, 100, 70};
        for (int index = 0; index < scores.length; index++)
            criteria(generated).get(index).put("score", scores[index]);
        criteria(generated).getFirst().put("evidence", List.of("- Built REST APIs."));
        writing(generated).put("_internal", "PRIVATE_MODEL_TRACE");
        enabled(generated);
        Map<String, Object> created = response(
            mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overall_score").value(86)).andExpect(jsonPath("$.content_score").value(90))
                .andExpect(jsonPath("$.writing_quality.score").value(70))
                .andExpect(jsonPath("$.writing_quality.assessed_criteria").value(5))
                .andExpect(jsonPath("$.writing_quality.criteria[0].code").value("BULLET_STRUCTURE")).andReturn()
        );
        String analysisId = created.get("id").toString();
        assertFalse(mapper.writeValueAsString(created).contains("PRIVATE_MODEL_TRACE"));
        Map<String, Object> persisted = db.one("SELECT * FROM resume_analyses WHERE id=?::uuid", analysisId);
        assertEquals("cv-writing-v1", ((Map<?, ?>) persisted.get("writing_quality_json")).get("rubric_version"));
        Map<?, ?> content = (Map<?, ?>) persisted.get("content_quality_json");
        assertEquals("cv-content-v1", content.get("rubric_version"));
        assertEquals(90.0, ((Number) content.get("score")).doubleValue());
        assertEquals(1, ((Number) content.get("assessed_criteria")).intValue());
        mvc.perform(get(path(resume) + "/" + analysisId).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.writing_quality.score").value(70))
            .andExpect(jsonPath("$.content_quality_json.score").value(90))
            .andExpect(jsonPath("$.suggestions[0].original_content").value("- Built REST APIs."));
        mvc.perform(get(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].writing_quality.score").value(70));
        verify(ai, times(1)).generate(eq("CV_ANALYZE"), argThat(input -> {
            Map<?, ?> source = (Map<?, ?>) input.get("resume");
            Map<?, ?> structured = (Map<?, ?>) source.get("structured_data");
            Map<?, ?> experience = (Map<?, ?>) ((List<?>) structured.get("experience")).getFirst();
            return DESCRIPTION.equals(experience.get("description"))
                && "REVIEWED_STRUCTURED".equals(source.get("content_authority")) && "vi".equals(input.get("language"))
                && !source.containsKey("template_id");
        }));
    }

    @Test
    void preservesReadyAndOwnershipGuardsBeforeProviderCallsAndAnalysisReads() throws Exception {
        UUID draft = resume("DRAFT"), ready = resume("READY");
        mvc.perform(post(path(draft)).header("Authorization", candidate)).andExpect(status().isConflict());
        mvc.perform(post(path(ready)).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(path(ready)).header("Authorization", other)).andExpect(status().isNotFound());
        enabled(ResumeWritingTestData.report());
        Map<String, Object> created = response(
            mvc.perform(post(path(ready)).header("Authorization", candidate)).andExpect(status().isOk()).andReturn()
        );
        mvc.perform(get(path(ready) + "/" + created.get("id")).header("Authorization", other))
            .andExpect(status().isNotFound());
        verify(ai, times(1)).generate(eq("CV_ANALYZE"), anyMap());
    }

    @Test
    void disabledAiUsesRulesForCompletenessAndAtsWithoutFabricatingWritingGrades() throws Exception {
        UUID resume = resume("READY");
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.analysis_type").value("RULE_BASED"))
            .andExpect(jsonPath("$.model_name").value("rules-v1"))
            .andExpect(jsonPath("$.writing_quality").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.content_score").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.ats_score").isNumber())
            .andExpect(jsonPath("$.ats_report.rubric_version").value("ats-v1"));
        assertNull(db.scalar("SELECT writing_quality_json FROM resume_analyses WHERE resume_id=?", resume));
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void historicalAnalysesStayReadableWithNullWritingDetails() throws Exception {
        UUID resume = resume("READY");
        UUID legacy = db.insert(
            "resume_analyses",
            Map.of(
                "resume_id",
                resume,
                "analysis_type",
                "AI_QUALITY",
                "overall_score",
                82,
                "summary",
                "Historical report"
            )
        );
        mvc.perform(get(path(resume) + "/" + legacy).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.overall_score").value(82))
            .andExpect(jsonPath("$.writing_quality").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.content_score").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(ai);
    }

    @Test
    void invalidRubricOrInventedEvidenceDoesNotPersistPartialAnalysesOrSuggestions() throws Exception {
        UUID resume = resume("READY");
        Map<String, Object> generated = ResumeWritingTestData.report();
        criteria(generated).getFirst().put("code", "PROFESSIONAL_WORDING");
        enabled(generated);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isBadGateway());
        criteria(generated).getFirst().put("code", "BULLET_STRUCTURE");
        criteria(generated).getFirst().put("evidence", List.of("Invented 50% performance improvement"));
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isBadGateway());
        criteria(generated).getFirst().put("evidence", List.of());
        generated.put("suggestions", List.of(Map.of("original_content", "Old unreviewed claim")));
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isBadGateway());
        assertEquals(0L, db.scalar("SELECT count(*) FROM resume_analyses WHERE resume_id=?", resume));
    }

    @Test
    void unobservableWritingReturnsContentOnlyAndRetainsExplicitNullCriteriaScores() throws Exception {
        UUID resume = resume("READY");
        Map<String, Object> generated = ResumeWritingTestData.report();
        for (Map<String, Object> criterion : criteria(generated)) {
            criterion.put("status", "INSUFFICIENT_EVIDENCE");
            criterion.put("score", null);
        }
        enabled(generated);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.overall_score").value(85))
            .andExpect(jsonPath("$.writing_quality.score").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.writing_quality.assessed_criteria").value(0))
            .andExpect(jsonPath("$.writing_quality.content_weight").value(1))
            .andExpect(jsonPath("$.writing_quality.writing_weight").value(0))
            .andExpect(jsonPath("$.writing_quality.criteria[0].score").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void contentEvidenceMustQuoteTheReviewedCvBeforeAnAnalysisCanBePersisted() throws Exception {
        UUID resume = resume("READY");
        for (String unsupported : List.of("Old unreviewed claim", "company_name", "Invented 50% improvement")) {
            Map<String, Object> generated = ResumeWritingTestData.report();
            generated.put("content_quality", ResumeWritingTestData.content(90, unsupported));
            enabled(generated);
            mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isBadGateway());
        }
        assertEquals(0L, db.scalar("SELECT count(*) FROM resume_analyses WHERE resume_id=?", resume));
        assertEquals(
            0L,
            db.scalar(
                "SELECT count(*) FROM resume_analysis_suggestions s JOIN resume_analyses a ON a.id=s.analysis_id "
                    + "WHERE a.resume_id=?",
                resume
            )
        );
    }

    @Test
    void editingAfterAnalysisRequiresReconfirmationBeforeAnotherEvaluation() throws Exception {
        UUID resume = resume("READY");
        enabled(ResumeWritingTestData.report());
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isOk());
        mvc.perform(
            patch("/api/v1/resumes/" + resume).header("Authorization", candidate)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Corrected CV title\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        verify(ai, times(1)).generate(eq("CV_ANALYZE"), anyMap());
    }

    private void enabled(
        Map<String, Object> report) {
        when(ai.enabled()).thenReturn(true);
        when(ai.generate(eq("CV_ANALYZE"), anyMap())).thenReturn(report);
    }

    private UUID resume(
        String status) {
        return db.insert(
            "resumes",
            Map.of(
                "user_id",
                owner,
                "title",
                "Writing quality CV",
                "language",
                "vi",
                "source",
                "MANUAL",
                "status",
                status,
                "raw_content",
                "Old unreviewed claim",
                "structured_data",
                db.json(
                    ResumeData.normalize(
                        Map.of(
                            "summary",
                            "Reviewed Java experience",
                            "experience",
                            List.of(Map.of("company_name", "Test Company", "description", DESCRIPTION))
                        )
                    )
                ),
                "content_hash",
                UUID.randomUUID().toString()
            )
        );
    }

    private UUID user() {
        return db.insert(
            "users",
            Map.of(
                "email",
                "cv-writing-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Writing Quality Test",
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

    private static String path(
        UUID resume) {
        return "/api/v1/resumes/" + resume + "/analyses";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(
        MvcResult result) {
        return mapper.readValue(
            new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8),
            Map.class
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> writing(
        Map<String, Object> report) {
        return (Map<String, Object>) report.get("writing_quality");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> criteria(
        Map<String, Object> report) {
        return (List<Map<String, Object>>) writing(report).get("criteria");
    }
}
