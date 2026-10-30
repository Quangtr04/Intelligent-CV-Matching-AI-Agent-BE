package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.time.OffsetDateTime;
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
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

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
class AssessmentJdIntegrationTests {

    private static final String JAVA_JD = "Build Java backend services and use SQL transactions safely.";

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
        owner = candidate();
        authorization = bearer(owner);
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(Map.of("questions", questions("Java")));
    }

    @Test
    void completedCvAndPrivatePastedJdAssessmentCanCreateItsResultBasedRoadmap() throws Exception {
        UUID resume = db.insert(
            "resumes",
            fields(
                "user_id",
                owner,
                "title",
                "Confirmed Java CV",
                "source",
                "MANUAL",
                "status",
                "READY",
                "raw_content",
                "Built Java backend services for production users."
            )
        );
        long catalogCount = ((Number) db.scalar("SELECT count(*) FROM market_job_posts")).longValue();
        var quiz = questions("Java");
        quiz.forEach(question -> question.put("cv_evidence", "Built Java backend services"));
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(fields("questions", quiz));
        var session =
            create(fields("resumeId", resume, "targetJobTitle", "Private backend role", "jdText", JAVA_JD), 200);
        String id = session.get("id").toString();
        assertNull(session.get("market_job_id"));
        assertEquals(resume.toString(), session.get("resume_id"));
        assertFalse(session.containsKey("context_snapshot"));

        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("assessmentId", id)))
        ).andExpect(status().isNotFound());
        for (Object raw : (List<?>) session.get("questions")) {
            mvc.perform(
                put("/api/v1/assessments/" + id + "/answers").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(
                            fields("questionId", ((Map<?, ?>) raw).get("id"), "selectedOption", "A")
                        )
                    )
            ).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roadmap_request.assessmentId").value(id));
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            fields(
                "title",
                "Practice SQL for this JD",
                "summary",
                "Focus on the required SQL skill.",
                "nodes",
                List.of(
                    fields(
                        "skill_name",
                        "SQL",
                        "description",
                        "Practice transaction rollback and commit.",
                        "estimated_hours",
                        4,
                        "prerequisites",
                        List.of()
                    )
                )
            )
        );
        var result = read(
            mvc.perform(
                post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(fields("assessmentId", id)))
            ).andExpect(status().isOk()).andReturn()
        );
        assertNull(result.get("market_job_id"));
        assertEquals("Private backend role", result.get("target_job_title"));
        assertEquals("SQL", ((Map<?, ?>) ((List<?>) result.get("missing_skills")).getFirst()).get("skill_name"));
        var gap =
            db.one("SELECT * FROM gap_analyses WHERE id=?", UUID.fromString(result.get("gap_analysis_id").toString()));
        assertEquals(resume, gap.get("resume_id"));
        assertEquals(UUID.fromString(id), gap.get("assessment_session_id"));
        assertTrue(gap.get("context_key").toString().startsWith("paste:"));
        assertNotNull(gap.get("input_hash"));
        assertEquals(catalogCount, ((Number) db.scalar("SELECT count(*) FROM market_job_posts")).longValue());

        db.update("UPDATE resumes SET raw_content='A different CV' WHERE id=?", resume);
        mvc.perform(
            post("/api/v1/roadmaps").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(fields("assessmentId", id)))
        ).andExpect(status().isConflict());
    }

    @Test
    void selectedJobIsRequiredEvenWhenTheProfileHasATargetCareer() throws Exception {
        db.update("INSERT INTO candidate_profiles(user_id,target_job_title) VALUES(?,'Software Engineer')", owner);
        create(Map.of("targetJobTitle", "Software Engineer"), 400);
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "REMOVED", "INVALID", "expired-date"})
    void inactiveOrExpiredJobsAreRejectedBeforeCallingAi(
        String state)
        throws Exception {
        UUID job = job("Java", JAVA_JD);
        if ("expired-date".equals(state))
            db.update(
                "UPDATE market_job_posts SET valid_through=? WHERE id=?",
                OffsetDateTime.now().minusHours(1),
                job
            );
        else
            db.update("UPDATE market_job_posts SET status=? WHERE id=?", state, job);
        create(Map.of("jobId", job), 404);
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void missingJobTextOrExtractedSkillsIsAnActionableConflict() throws Exception {
        UUID noText = job("Java", "");
        create(Map.of("jobId", noText), 409);
        UUID noSkills = job("SQL", JAVA_JD);
        db.update("DELETE FROM market_job_skills WHERE market_job_id=?", noSkills);
        create(Map.of("jobId", noSkills), 409);
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void aDifferentTargetCannotReplaceTheSelectedJobAndNormalizedTitlesUseItsCanonicalTitle() throws Exception {
        UUID job = job("Java", JAVA_JD);
        create(Map.of("jobId", job, "targetJobTitle", "Marketing Manager"), 400);
        verify(ai, never()).generate(anyString(), anyMap());
        var session = create(Map.of("jobId", job, "targetJobTitle", "  software   engineer  "), 200);
        assertEquals("Software Engineer", session.get("target_job_title"));
        assertEquals(job.toString(), session.get("market_job_id"));
        assertEquals(5, ((Number) session.get("part1_count")).intValue());
        assertEquals(5, ((Number) session.get("part2_count")).intValue());
    }

    @Test
    void jobsWithTheSameTitlePassDifferentActualDescriptionsAndCanonicalSkillsToAi() throws Exception {
        UUID java = job("Java", JAVA_JD);
        String pythonDescription = "Develop Python data pipelines and validate event processing results.";
        UUID python = job("Python", pythonDescription);
        List<Map<String, Object>> captured = new ArrayList<>();
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenAnswer(call -> {
            Map<String, Object> input = call.getArgument(1);
            captured.add(input);
            boolean javaJob = input.get("jd_text").toString().contains("Java");
            return Map.of("questions", questions(javaJob ? "Java" : "Python"));
        });
        var javaSession = create(Map.of("jobId", java), 200);
        var pythonSession = create(Map.of("jobId", python), 200);
        assertEquals(2, captured.size());
        assertEquals(JAVA_JD, captured.getFirst().get("jd_text"));
        assertEquals(pythonDescription, captured.getLast().get("jd_text"));
        assertEquals(
            "Java",
            ((Map<?, ?>) ((List<?>) captured.getFirst().get("jd_skills")).getFirst()).get("skill_name")
        );
        assertEquals(
            "Python",
            ((Map<?, ?>) ((List<?>) captured.getLast().get("jd_skills")).getFirst()).get("skill_name")
        );
        assertEquals(5, captured.getFirst().get("foundation_count"));
        assertEquals(5, captured.getFirst().get("role_specific_count"));
        create(Map.of("jobId", java), 200);
        assertEquals(
            false,
            db.scalar(
                "SELECT is_current FROM assessment_sessions WHERE id=?",
                UUID.fromString(javaSession.get("id").toString())
            )
        );
        assertEquals(
            true,
            db.scalar(
                "SELECT is_current FROM assessment_sessions WHERE id=?",
                UUID.fromString(pythonSession.get("id").toString())
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"count", "parts", "unrelated-skill", "fabricated-evidence", "duplicate-question", "choices"}
    )
    void invalidOrUngroundedGeneratedQuizzesAreNotPersisted(
        String kind)
        throws Exception {
        UUID job = job("Java", JAVA_JD);
        List<Map<String, Object>> quiz = questions("Java");
        switch (kind) {
            case "count" -> quiz.removeLast();
            case "parts" -> quiz.get(5).put("part", 1);
            case "unrelated-skill" -> quiz.getFirst().put("related_skill_name", "React");
            case "fabricated-evidence" -> quiz.getFirst().put("jd_evidence", "A made-up React requirement");
            case "duplicate-question" -> quiz.get(1).put("question_text", quiz.getFirst().get("question_text"));
            case "choices" -> quiz.getFirst().put("options", Map.of("A", "same", "B", "same", "C", "C", "D", "D"));
            default -> throw new AssertionError(kind);
        }
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(Map.of("questions", quiz));
        create(Map.of("jobId", job), 502);
        assertEquals(
            0L,
            ((Number) db.scalar("SELECT count(*) FROM assessment_sessions WHERE user_id=?", owner)).longValue()
        );
    }

    @Test
    void questionsHideAnswersUntilSubmissionAndEnforceOwnershipAndCompleteServerScoring() throws Exception {
        UUID job = job("Java", JAVA_JD);
        UUID sameTitleJob = job("Java", JAVA_JD);
        UUID ownRoadmap = db.insert(
            "roadmaps",
            fields(
                "user_id",
                owner,
                "market_job_id",
                job,
                "target_job_title",
                "Software Engineer",
                "title",
                "Selected JD skills"
            )
        );
        UUID unrelatedRoadmap = db.insert(
            "roadmaps",
            fields(
                "user_id",
                owner,
                "market_job_id",
                sameTitleJob,
                "target_job_title",
                "Software Engineer",
                "title",
                "Other JD skills"
            )
        );
        db.insert("market_job_skills", fields("market_job_id", job, "skill_name", "SQL", "normalized_skill", "sql"));
        List<Map<String, Object>> quiz = questions("Java");
        for (int index = 5; index < 10; index++)
            quiz.get(index).put("related_skill_name", "sql");
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(Map.of("questions", quiz));
        var session = create(Map.of("jobId", job), 200);
        String id = session.get("id").toString();
        var questions = (List<?>) session.get("questions");
        assertEquals(10, questions.size());
        for (Object raw : questions) {
            var question = (Map<?, ?>) raw;
            assertFalse(question.containsKey("correct_option"));
            assertFalse(question.containsKey("explanation"));
        }
        mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", authorization))
            .andExpect(status().isBadRequest());
        String other = bearer(candidate());
        mvc.perform(get("/api/v1/assessments/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        for (int index = 0; index < questions.size(); index++) {
            String questionId = ((Map<?, ?>) questions.get(index)).get("id").toString();
            String choice = index == 3 || index == 4 || index == 9 ? "B" : "A";
            String body = mapper.writeValueAsString(Map.of("questionId", questionId, "selectedOption", choice));
            if (index == 0)
                mvc.perform(
                    put("/api/v1/assessments/" + id + "/answers").header("Authorization", other)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                ).andExpect(status().isNotFound());
            mvc.perform(
                put("/api/v1/assessments/" + id + "/answers").header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk());
        }
        var completed = read(
            mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", authorization))
                .andExpect(status().isOk()).andReturn()
        );
        assertEquals("completed", completed.get("status"));
        var scores = (List<?>) completed.get("skill_scores");
        assertEquals(2, scores.size());
        assertEquals(60.0, ((Number) ((Map<?, ?>) scores.getFirst()).get("score")).doubleValue());
        assertEquals(80.0, ((Number) ((Map<?, ?>) scores.getLast()).get("score")).doubleValue());
        assertTrue(((Map<?, ?>) ((List<?>) completed.get("questions")).getFirst()).containsKey("correct_option"));
        assertEquals(true, db.scalar("SELECT is_outdated FROM roadmaps WHERE id=?", ownRoadmap));
        assertEquals(false, db.scalar("SELECT is_outdated FROM roadmaps WHERE id=?", unrelatedRoadmap));
        mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", authorization))
            .andExpect(status().isOk());
        mvc.perform(
            put("/api/v1/assessments/" + id + "/answers").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(
                    mapper.writeValueAsString(
                        Map.of("questionId", ((Map<?, ?>) questions.getFirst()).get("id"), "selectedOption", "A")
                    )
                )
        ).andExpect(status().isConflict());
    }

    private List<Map<String, Object>> questions(
        String skill) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            result.add(
                new LinkedHashMap<>(
                    Map.of(
                        "part",
                        index < 5 ? 1 : 2,
                        "question_text",
                        "How should " + skill + " requirement " + index + " be implemented?",
                        "options",
                        Map.of(
                            "A",
                            "Correct implementation",
                            "B",
                            "Incorrect design",
                            "C",
                            "Skip validation",
                            "D",
                            "Ignore failures"
                        ),
                        "correct_option",
                        "A",
                        "related_skill_name",
                        skill.toLowerCase(),
                        "jd_evidence",
                        "Java".equals(skill) ? "Build Java backend services" : "Develop Python data pipelines",
                        "explanation",
                        "The selected job requires a safe and correct " + skill + " implementation."
                    )
                )
            );
        }
        return result;
    }

    private UUID candidate() {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@assessment.test",
                "full_name",
                "JD Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "CANDIDATE"
            )
        );
    }

    private String bearer(
        UUID id) {
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private UUID job(
        String skill,
        String description) {
        UUID id = db.insert(
            "market_job_posts",
            fields(
                "source_site",
                "ITVIEC",
                "raw_content_hash",
                UUID.randomUUID().toString(),
                "job_title",
                "Software Engineer",
                "role_category",
                "Backend",
                "raw_content",
                description
            )
        );
        db.insert(
            "market_job_skills",
            fields("market_job_id", id, "skill_name", skill, "normalized_skill", skill.toLowerCase())
        );
        return id;
    }

    private Map<String, Object> create(
        Map<String, Object> input,
        int status)
        throws Exception {
        var result = mvc.perform(
            post("/api/v1/assessments").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))
        ).andExpect(status().is(status)).andReturn();
        return read(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }
}
