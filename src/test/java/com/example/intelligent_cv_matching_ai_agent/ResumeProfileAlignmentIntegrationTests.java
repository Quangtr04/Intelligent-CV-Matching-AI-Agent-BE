package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
class ResumeProfileAlignmentIntegrationTests {

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

    private UUID resume;

    private String candidate;

    private String other;

    @BeforeEach
    void fixture() {
        reset(ai);
        owner = user("CANDIDATE");
        candidate = token(owner);
        other = token(user("CANDIDATE"));
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title,expected_salary,salary_currency)"
                + " VALUES(?,'Java Developer',7500000,'VND')",
            owner
        );
        db.update(
            "INSERT INTO candidate_profile_skills(user_id,skill_id,proficiency_level,years_experience)"
                + " VALUES(?,'20000000-0000-0000-0000-000000000001','beginner',0.5)",
            owner
        );
        resume = resume(owner, "READY");
        when(ai.enabled()).thenReturn(true);
        when(ai.model()).thenReturn("fallback-model");
        when(ai.generate(eq("CV_PROFILE_ALIGNMENT"), anyMap())).thenAnswer(ignored -> generated());
    }

    @Test
    void comparesOnlyTheOwnedConfirmedCvAndCurrentServerProfileThenStoresAnIndependentHistory() throws Exception {
        var created = response(
            mvc.perform(
                post(path(resume)).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"career_profile\":{\"target_job_title\":\"Client injected role\"}}")
            ).andExpect(status().isOk()).andExpect(jsonPath("$.result_json.score").value(90))
                .andExpect(jsonPath("$.result_json.criteria.length()").value(8))
                .andExpect(jsonPath("$.resume_changed").value(false))
                .andExpect(jsonPath("$.profile_changed").value(false)).andExpect(jsonPath("$.is_stale").value(false))
                .andReturn()
        );
        assertEquals("unit-alignment-model", created.get("model_name"));
        assertEquals(64, created.get("profile_hash").toString().length());
        assertEquals(64, created.get("resume_content_hash").toString().length());
        assertEquals(1L, count());
        assertEquals(0L, db.scalar("SELECT count(*) FROM resume_analyses WHERE resume_id=?", resume));
        Map<?, ?> result = (Map<?, ?>) created.get("result_json");
        Map<?, ?> salary = (Map<?, ?>) result.get("salary_expectation");
        assertEquals(7500000.0, ((Number) salary.get("expected_salary")).doubleValue());
        assertEquals("REQUIRES_JOB_DATA", salary.get("status"));
        assertTrue(salary.containsKey("score"));
        assertNull(salary.get("score"));
        verify(ai).generate(eq("CV_PROFILE_ALIGNMENT"), argThat(input -> {
            String json = mapper.writeValueAsString(input);
            return json.contains("Java Developer") && json.contains("Java") && !json.contains("Client injected role")
                && !json.contains("password_hash") && !json.contains("profile-alignment-")
                && !json.contains("old raw extraction");
        }));
    }

    @Test
    void historyReadsFrozenSnapshotsWithoutAiAndShowsProfileAndResumeStalenessSeparately() throws Exception {
        var created = create();
        UUID id = UUID.fromString(created.get("id").toString());
        Map<String, Object> stored = db.one("SELECT * FROM resume_profile_alignments WHERE id=?", id);
        clearInvocations(ai);
        mvc.perform(get(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id.toString())).andExpect(jsonPath("$[0].is_stale").value(false));
        db.update("UPDATE candidate_profiles SET target_job_title='Python Developer' WHERE user_id=?", owner);
        mvc.perform(get(path(resume) + "/" + id).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.profile_changed").value(true)).andExpect(jsonPath("$.resume_changed").value(false))
            .andExpect(jsonPath("$.profile_snapshot.target_job_title").value("Java Developer"))
            .andExpect(jsonPath("$.result_json.score").value(90));
        db.update(
            "UPDATE resumes SET content_hash=?,structured_data=? WHERE id=?",
            FlowSupport.hash("changed content"),
            db.json(Map.of("summary", "New reviewed content")),
            resume
        );
        mvc.perform(get(path(resume) + "/" + id).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.profile_changed").value(true)).andExpect(jsonPath("$.resume_changed").value(true))
            .andExpect(jsonPath("$.is_stale").value(true)).andExpect(jsonPath("$.result_json.score").value(90));
        assertEquals(stored, db.one("SELECT * FROM resume_profile_alignments WHERE id=?", id));
        verifyNoInteractions(ai);
    }

    @Test
    void profileTimestampsAndTemplateChangesDoNotInvalidateIdenticalComparisonInputs() throws Exception {
        var created = create();
        db.update("UPDATE candidate_profiles SET updated_at=now()+interval '1 hour' WHERE user_id=?", owner);
        db.update("UPDATE resumes SET template_id='30000000-0000-0000-0000-000000000001' WHERE id=?", resume);
        clearInvocations(ai);
        mvc.perform(get(path(resume) + "/" + created.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.is_stale").value(false));
        verifyNoInteractions(ai);
    }

    @Test
    void aSkillOnlyProfileChangeMakesHistoricalComparisonStale() throws Exception {
        var created = create();
        addProfileSkill();
        clearInvocations(ai);
        mvc.perform(get(path(resume) + "/" + created.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile_changed").value(true))
            .andExpect(jsonPath("$.resume_changed").value(false));
        verifyNoInteractions(ai);
    }

    @Test
    void anUnassessedComparisonPersistsExplicitNullScoresWithoutInventingASalaryFit() throws Exception {
        var generated = generated();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) generated.get("criteria");
        criteria.forEach(row -> {
            if ("ASSESSED".equals(row.get("status"))) {
                row.put("status", "INSUFFICIENT_EVIDENCE");
                row.put("score", null);
                row.put("profile_evidence", List.of());
                row.put("cv_evidence", List.of());
            }
        });
        when(ai.generate(eq("CV_PROFILE_ALIGNMENT"), anyMap())).thenReturn(generated);
        var created = create();
        Map<?, ?> result = (Map<?, ?>) created.get("result_json");
        Map<?, ?> salary = (Map<?, ?>) result.get("salary_expectation");
        assertTrue(result.containsKey("score"));
        assertNull(result.get("score"));
        assertEquals(0, ((Number) result.get("assessed_criteria")).intValue());
        assertEquals(0.0, ((Number) result.get("coverage_percent")).doubleValue());
        assertTrue(salary.containsKey("score"));
        assertNull(salary.get("score"));
        assertEquals("REQUIRES_JOB_DATA", salary.get("status"));
        clearInvocations(ai);
        var historical = response(
            mvc.perform(get(path(resume) + "/" + created.get("id")).header("Authorization", candidate))
                .andExpect(status().isOk()).andReturn()
        );
        assertEquals(result, historical.get("result_json"));
        verifyNoInteractions(ai);
    }

    @Test
    void aRemovedCareerProfileLeavesSavedComparisonsReadableAndMarksThemStale() throws Exception {
        var created = create();
        db.update("DELETE FROM candidate_profile_skills WHERE user_id=?", owner);
        db.update("DELETE FROM candidate_profiles WHERE user_id=?", owner);
        clearInvocations(ai);
        mvc.perform(get(path(resume) + "/" + created.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile_changed").value(true))
            .andExpect(jsonPath("$.resume_changed").value(false)).andExpect(jsonPath("$.is_stale").value(true))
            .andExpect(jsonPath("$.profile_snapshot.target_job_title").value("Java Developer"))
            .andExpect(jsonPath("$.result_json.score").value(90));
        verifyNoInteractions(ai);
    }

    @Test
    void deletingASkillsOnlyProfileMarksHistoryStaleEvenWhenItsSkillRowsRemain() throws Exception {
        db.update(
            "UPDATE candidate_profiles SET target_job_title=NULL,expected_salary=NULL,salary_currency=NULL"
                + " WHERE user_id=?",
            owner
        );
        var created = create();
        UUID alignment = UUID.fromString(created.get("id").toString());
        Map<String, Object> stored = db.one("SELECT * FROM resume_profile_alignments WHERE id=?", alignment);
        db.update("DELETE FROM candidate_profiles WHERE user_id=?", owner);
        assertEquals(1L, db.scalar("SELECT count(*) FROM candidate_profile_skills WHERE user_id=?", owner));
        clearInvocations(ai);
        mvc.perform(get(path(resume) + "/" + alignment).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.profile_changed").value(true)).andExpect(jsonPath("$.resume_changed").value(false))
            .andExpect(jsonPath("$.is_stale").value(true))
            .andExpect(jsonPath("$.profile_snapshot.skills[0].name").value("Java"))
            .andExpect(jsonPath("$.result_json.score").value(100));
        mvc.perform(get(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].profile_changed").value(true)).andExpect(jsonPath("$[0].is_stale").value(true));
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        assertEquals(stored, db.one("SELECT * FROM resume_profile_alignments WHERE id=?", alignment));
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED", "PARSING", "INVALID", "ARCHIVED"})
    void unconfirmedCvCannotConsumeInference(
        String status)
        throws Exception {
        db.update("UPDATE resumes SET status=? WHERE id=?", status, resume);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(0L, count());
    }

    @Test
    void missingOrSalaryOnlyCareerProfileRequiresMeaningfulCareerInformation() throws Exception {
        db.update("DELETE FROM candidate_profiles WHERE user_id=?", owner);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        db.update("DELETE FROM candidate_profile_skills WHERE user_id=?", owner);
        db.update(
            "INSERT INTO candidate_profiles(user_id,expected_salary,salary_currency) VALUES(?,7500000,'VND')",
            owner
        );
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(0L, count());
    }

    @Test
    void authenticationRoleOwnershipAndAlignmentScopeAreEnforcedBeforeInference() throws Exception {
        mvc.perform(post(path(resume))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(resume))).andExpect(status().isUnauthorized());
        String admin = token(user("ADMIN"));
        mvc.perform(post(path(resume)).header("Authorization", admin)).andExpect(status().isForbidden());
        mvc.perform(get(path(resume)).header("Authorization", admin)).andExpect(status().isForbidden());
        mvc.perform(post(path(resume)).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(path(resume)).header("Authorization", other)).andExpect(status().isNotFound());
        verify(ai, never()).generate(anyString(), anyMap());
        var created = create();
        mvc.perform(get(path(resume) + "/" + created.get("id")).header("Authorization", other))
            .andExpect(status().isNotFound());
        UUID otherCv = resume(owner, "READY");
        mvc.perform(get(path(otherCv) + "/" + created.get("id")).header("Authorization", candidate))
            .andExpect(status().isNotFound());
    }

    @Test
    void disabledAiReturnsAnExplicitUnavailableResultWithoutAHistoryRow() throws Exception {
        when(ai.enabled()).thenReturn(false);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isServiceUnavailable());
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(0L, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROFILE", "SKILLS", "CV", "STATUS"})
    void changedInputsDuringInferenceRejectTheResultBeforeAnyHistoryInsert(
        String change)
        throws Exception {
        when(ai.generate(eq("CV_PROFILE_ALIGNMENT"), anyMap())).thenAnswer(ignored -> {
            switch (change) {
                case "PROFILE" -> db
                    .update("UPDATE candidate_profiles SET target_job_title='Python Developer' WHERE user_id=?", owner);
                case "SKILLS" -> addProfileSkill();
                case "CV" -> db.update(
                    "UPDATE resumes SET structured_data=? WHERE id=?",
                    db.json(Map.of("summary", "Changed without trusting a stale content_hash")),
                    resume
                );
                case "STATUS" -> db.update("UPDATE resumes SET status='REVIEW_REQUIRED' WHERE id=?", resume);
                default -> throw new IllegalArgumentException(change);
            }
            return generated();
        });
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        assertEquals(0L, count());
    }

    @Test
    void invalidModelEvidenceCannotPersistPartialHistory() throws Exception {
        var generated = generated();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) generated.get("criteria");
        criteria.getFirst().put("cv_evidence", List.of("Invented 50 percent improvement"));
        when(ai.generate(eq("CV_PROFILE_ALIGNMENT"), anyMap())).thenReturn(generated);
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isBadGateway());
        assertEquals(0L, count());
    }

    @Test
    void historyPaginationRejectsUnboundedRequestsAndMissingIds() throws Exception {
        mvc.perform(get(path(resume)).header("Authorization", candidate).param("limit", "0"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(path(resume)).header("Authorization", candidate).param("limit", "101"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(path(resume)).header("Authorization", candidate).param("offset", "-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(path(resume) + "/" + UUID.randomUUID()).header("Authorization", candidate))
            .andExpect(status().isNotFound());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void migrationClonesExistingQualityFeatureQuotasWithoutInventingTierValues() {
        assertEquals(
            0L,
            db.scalar(
                "SELECT count(*) FROM subscription_quotas source"
                    + " LEFT JOIN subscription_quotas copied ON copied.tier_id=source.tier_id"
                    + " AND copied.feature_code='CV_PROFILE_ALIGNMENT' WHERE source.feature_code='CV_ANALYZE'"
                    + " AND (copied.id IS NULL OR copied.quota_value<>source.quota_value"
                    + " OR copied.quota_period<>source.quota_period)"
            )
        );
    }

    private void addProfileSkill() {
        UUID skill = db.insert(
            "skills",
            Map.of("name", "Alignment Skill " + UUID.randomUUID(), "slug", "alignment-skill-" + UUID.randomUUID())
        );
        db.update("INSERT INTO candidate_profile_skills(user_id,skill_id) VALUES(?,?)", owner, skill);
    }

    private long count() {
        return ((Number) db.scalar("SELECT count(*) FROM resume_profile_alignments WHERE resume_id=?", resume))
            .longValue();
    }

    private Map<String, Object> create() throws Exception {
        return response(
            mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isOk()).andReturn()
        );
    }

    private Map<String, Object> response(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private static String path(
        UUID resume) {
        return "/api/v1/resumes/" + resume + "/profile-alignments";
    }

    private UUID user(
        String role) {
        return db.insert(
            "users",
            Map.of(
                "email",
                "profile-alignment-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Alignment Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
    }

    private String token(
        UUID id) {
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private UUID resume(
        UUID owner,
        String status) {
        Map<String, Object> reviewed = ResumeData.normalize(
            Map.of("summary", "Java Developer with Java project experience", "skills", List.of(Map.of("name", "Java")))
        );
        return db.insert(
            "resumes",
            Map.of(
                "user_id",
                owner,
                "title",
                "Alignment CV",
                "language",
                "en",
                "source",
                "MANUAL",
                "status",
                status,
                "raw_content",
                "An old raw extraction must not override reviewed wording",
                "structured_data",
                db.json(reviewed),
                "content_hash",
                FlowSupport.hash("initial alignment CV")
            )
        );
    }

    private static Map<String, Object> generated() {
        List<Map<String, Object>> criteria = new ArrayList<>();
        for (String code : List
            .of("TARGET_ROLE", "CAREER_GOAL", "SKILLS", "EXPERIENCE", "LOCATION", "WORKPLACE", "EMPLOYMENT_TYPE")) {
            Map<String, Object> row = new LinkedHashMap<>();
            boolean assessed = code.equals("TARGET_ROLE") || code.equals("SKILLS");
            String quote = code.equals("TARGET_ROLE") ? "Java Developer" : "Java";
            row.put("code", code);
            row.put("status", assessed ? "ASSESSED" : "NOT_APPLICABLE");
            row.put("score", assessed ? code.equals("TARGET_ROLE") ? 80 : 100 : null);
            row.put(
                "feedback",
                assessed
                    ? "Both sources contain the stated career information."
                    : "The profile does not specify this preference."
            );
            row.put("profile_evidence", assessed ? List.of(quote) : List.of());
            row.put("cv_evidence", assessed ? List.of(quote) : List.of());
            row.put("suggestions", List.of());
            criteria.add(row);
        }
        return new LinkedHashMap<>(
            Map.of(
                "rubric_version",
                "cv-profile-alignment-v1",
                "criteria",
                criteria,
                "_model",
                "unit-alignment-model",
                "score",
                1
            )
        );
    }
}
