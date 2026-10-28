package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

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
class MissingSkillLearningIntegrationTests {

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
    void fixture() {
        reset(ai);
        owner = user();
        authorization = "Bearer " + tokens.issue(owner).get("accessToken");
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title) VALUES (?,?)",
            owner,
            "Unrelated old career"
        );
    }

    @Test
    void createsOnlyMissingJdSkillsAndPersistsTheEvidenceClassificationAndGroupedGuidance() throws Exception {
        UUID job = job("Java", "Docker");
        declared("Java");
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(generated("Docker"));

        var result = create(fields("jobId", job, "targetJobTitle", "Ignored spoofed target"));

        assertEquals("MISSING_SKILLS_ONLY", result.get("learning_scope"));
        assertEquals("Chosen JD", result.get("target_job_title"));
        var gap = object(array(result.get("missing_skills")).getFirst());
        assertEquals("Docker", gap.get("skill_name"));
        assertEquals("MISSING_EVIDENCE", gap.get("gap_status"));
        assertEquals(1, array(gap.get("nodes")).size());
        assertTrue(array(object(result.get("learning_recommendations")).get("roadmaps")).isEmpty());
        verify(ai)
            .generate(
                eq("ROADMAP_GENERATE"),
                argThat(
                    input -> "MISSING_SKILLS_ONLY".equals(input.get("learning_scope"))
                        && array(input.get("allowed_missing_skills")).size() == 1
                        && "Docker"
                            .equals(object(array(input.get("allowed_missing_skills")).getFirst()).get("skill_name"))
                )
            );
        var history = read(result.get("id"));
        assertEquals(result.get("missing_skills"), history.get("missing_skills"));
    }

    @Test
    void roadmapInferenceReleasesTheTransactionAndKeepsLargePrivateSnapshotsOutOfThePrompt() throws Exception {
        UUID job = job("Docker");
        db.update(
            "UPDATE market_job_posts SET raw_content=?,parsed_requirements=? WHERE id=?",
            "Docker deployment context. ".repeat(5000),
            "Build container images with Docker.",
            job
        );
        db.update("UPDATE candidate_profiles SET career_goal=? WHERE user_id=?", "private-goal".repeat(5000), owner);
        UUID resume = db.insert(
            "resumes",
            fields(
                "user_id",
                owner,
                "title",
                "Confirmed CV",
                "source",
                "MANUAL",
                "status",
                "READY",
                "raw_content",
                "private-cv-content".repeat(5000)
            )
        );
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Map<String, Object> input = call.getArgument(1);
            assertFalse(input.containsKey("job"));
            assertFalse(input.containsKey("resume"));
            assertFalse(input.containsKey("assessment"));
            assertFalse(input.containsKey("learning_references"));
            assertTrue(text(input.get("jd_text")).length() <= 4800);
            assertTrue(text(input.get("jd_text")).contains("Build container images with Docker."));
            assertFalse(mapper.writeValueAsString(input).contains("private-"));
            return generated("Docker");
        });

        var result = create(fields("jobId", job, "resumeId", resume));

        assertEquals(1, array(result.get("nodes")).size());
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        verify(ai, times(1)).generate(eq("ROADMAP_GENERATE"), anyMap());
    }

    @Test
    void validLearningNodesPublishWithoutAskingAiToRepeatTheServersSkillGapAnalysis() throws Exception {
        UUID job = job("Java", "Docker");
        declared("Java");
        Map<String, Object> guidance = generated("Docker");
        guidance.remove("skill_gaps");
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(guidance);

        var result = create(fields("jobId", job));

        var gap = object(array(result.get("skill_gaps")).getFirst());
        assertEquals("Docker", gap.get("skill_name"));
        assertEquals("MISSING_EVIDENCE", gap.get("gap_status"));
        assertEquals(1, array(result.get("nodes")).size());
        assertEquals(1L, ((Number) db.scalar("SELECT count(*) FROM roadmaps WHERE user_id=?", owner)).longValue());
        assertEquals(1L, ((Number) db.scalar("SELECT count(*) FROM gap_analyses WHERE user_id=?", owner)).longValue());
        assertEquals(result.get("skill_gaps"), read(result.get("id")).get("skill_gaps"));
    }

    @Test
    void anOwnedReadyCvCompletesDeclaredEvidenceAndNoAiIsCalledWhenNothingIsMissing() throws Exception {
        UUID job = job("Java", "SQL");
        declared("Java");
        UUID cv = db.insert(
            "resumes",
            fields("user_id", owner, "title", "Confirmed CV", "source", "MANUAL", "status", "READY")
        );
        db.update("INSERT INTO resume_skills(resume_id,skill_id) VALUES(?,?)", cv, skill("SQL"));

        var result = create(fields("jobId", job, "resumeId", cv));

        assertEquals("completed", result.get("status"));
        assertEquals(100, ((Number) result.get("progress_percent")).intValue());
        assertTrue(array(result.get("missing_skills")).isEmpty());
        assertTrue(array(result.get("nodes")).isEmpty());
        assertEquals(result.get("missing_skills"), read(result.get("id")).get("missing_skills"));
        verifyNoInteractions(ai);
    }

    @Test
    void aWeakSameJdAssessmentOverridesDeclaredSkillsAndReportsTheMeasuredScore() throws Exception {
        UUID job = job("Java", "Docker");
        declared("Java");
        declared("Docker");
        assessment(job, fields("skill", "Docker", "score", 50), fields("skill", "Java", "score", 90));
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(generated("Docker"));

        var gap = object(array(create(fields("jobId", job)).get("missing_skills")).getFirst());

        assertEquals("NEEDS_PRACTICE", gap.get("gap_status"));
        assertEquals(50.0, ((Number) gap.get("assessment_score")).doubleValue());
        assertTrue(text(gap.get("reasoning")).contains("below 70"));
    }

    @Test
    void anAssessmentFromAnotherJdWithTheSameTitleCannotEstablishEvidenceOrBeSelectedExplicitly() throws Exception {
        UUID selected = job("Docker");
        UUID otherJob = job("Docker");
        UUID assessment = assessment(otherJob, fields("skill", "Docker", "score", 100));
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(generated("Docker"));

        var result = create(fields("jobId", selected));
        assertEquals("MISSING_EVIDENCE", object(array(result.get("missing_skills")).getFirst()).get("gap_status"));
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", selected, "assessmentId", assessment)))
        ).andExpect(status().isBadRequest());
    }

    @Test
    void aiCannotIntroduceKnownOrUnrelatedSkillsAndFailedOutputDoesNotPersistAnyPlan() throws Exception {
        UUID job = job("Java", "Docker");
        declared("Java");
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(generated("Java"));

        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", job)))
        ).andExpect(status().isBadGateway());
        assertEquals(0L, ((Number) db.scalar("SELECT count(*) FROM roadmaps WHERE user_id=?", owner)).longValue());
        assertEquals(0L, ((Number) db.scalar("SELECT count(*) FROM gap_analyses WHERE user_id=?", owner)).longValue());
    }

    @Test
    void jobSelectionAndStructuredRequiredSkillsAreMandatoryAndExpiredJobsAreUnavailable() throws Exception {
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetJobTitle\":\"Backend\"}")
        ).andExpect(status().isBadRequest());
        UUID empty = job();
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", empty)))
        ).andExpect(status().isConflict());
        UUID expired = job("Java");
        db.update("UPDATE market_job_posts SET valid_through=now()-interval '1 hour' WHERE id=?", expired);
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", expired)))
        ).andExpect(status().isNotFound());
        verifyNoInteractions(ai);
    }

    @Test
    void foreignCvAndUnconfirmedCvCannotBeUsedAsSkillEvidence() throws Exception {
        UUID job = job("Java");
        UUID foreign =
            db.insert("resumes", fields("user_id", user(), "title", "Foreign", "source", "MANUAL", "status", "READY"));
        UUID draft =
            db.insert("resumes", fields("user_id", owner, "title", "Draft", "source", "MANUAL", "status", "DRAFT"));
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", job, "resumeId", foreign)))
        ).andExpect(status().isNotFound());
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("jobId", job, "resumeId", draft)))
        ).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @Test
    void emptySkillRecommendationsDoNotReturnAnEntireRoleRoadmap() throws Exception {
        var response = mvc.perform(
            get("/api/v1/roadmaps/recommendations").header("Authorization", authorization)
                .param("targetJobTitle", "Backend Developer")
        ).andExpect(status().isOk()).andReturn().getResponse();
        var result = mapper.readValue(response.getContentAsString(), Map.class);
        assertTrue(array(result.get("resources")).isEmpty());
        assertTrue(array(result.get("roadmaps")).isEmpty());
        verifyNoInteractions(ai);
    }

    private UUID user() {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@missing-skill.test",
                "full_name",
                "Fixture",
                "password_hash",
                "test-only-unusable-hash",
                "role",
                "CANDIDATE",
                "status",
                "ACTIVE"
            )
        );
    }

    private UUID job(
        String... skills) {
        UUID id = db.insert(
            "market_job_posts",
            fields(
                "source_site",
                "TEST",
                "raw_content_hash",
                UUID.randomUUID().toString(),
                "job_title",
                "Chosen JD",
                "role_category",
                "BACKEND",
                "raw_content",
                "Required skills: " + String.join(", ", skills)
            )
        );
        for (String skill : skills) {
            db.insert(
                "market_job_skills",
                fields(
                    "market_job_id",
                    id,
                    "skill_name",
                    skill,
                    "normalized_skill",
                    skill.toLowerCase(Locale.ROOT),
                    "is_required",
                    true
                )
            );
        }
        return id;
    }

    private UUID skill(
        String name) {
        return (UUID) db.optional("SELECT id FROM skills WHERE lower(name)=lower(?)", name)
            .orElseGet(
                () -> fields(
                    "id",
                    db.insert(
                        "skills",
                        fields("name", name, "slug", "fixture-" + UUID.randomUUID(), "category", "Technical")
                    )
                )
            ).get("id");
    }

    private void declared(
        String name) {
        db.update("INSERT INTO candidate_profile_skills(user_id,skill_id) VALUES(?,?)", owner, skill(name));
    }

    @SafeVarargs
    private UUID assessment(
        UUID job,
        Map<String, Object>... scores) {
        return db.insert(
            "assessment_sessions",
            fields(
                "user_id",
                owner,
                "market_job_id",
                job,
                "target_job_title",
                "Chosen JD",
                "status",
                "completed",
                "submitted_at",
                OffsetDateTime.now(),
                "skill_scores",
                db.json(Arrays.asList(scores))
            )
        );
    }

    private Map<String, Object> generated(
        String skill) {
        return fields(
            "title",
            "Practice missing skills",
            "summary",
            "Targeted JD guidance",
            "skill_gaps",
            List.of(
                fields(
                    "skill_name",
                    skill,
                    "gap_status",
                    "MISSING_EVIDENCE",
                    "urgency_score",
                    80,
                    "reasoning",
                    "Model reasoning replaced by server evidence"
                )
            ),
            "nodes",
            List.of(
                fields(
                    "skill_name",
                    skill,
                    "description",
                    "Practice a small task using " + skill,
                    "estimated_hours",
                    3,
                    "prerequisites",
                    List.of()
                )
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> create(
        Map<String, Object> body)
        throws Exception {
        var response = mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        ).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readValue(response.getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(
        Object id)
        throws Exception {
        var response = mvc.perform(get("/api/v1/roadmaps/" + id).header("Authorization", authorization))
            .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readValue(response.getContentAsString(), Map.class);
    }
}
