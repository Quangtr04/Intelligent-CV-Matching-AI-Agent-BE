package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentGenerationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentGenerationService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
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
        "app.resumes.profile-alignment.refresh.enabled=false",
        "app.assessments.generation.initial-delay-ms=3600000",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AssessmentGenerationIntegrationTests {

    private static final String PATH = "/api/v1/assessments/generations";

    private static final String JD = "Build Java backend services and use SQL transactions safely.";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private TokenService tokens;

    @Autowired
    private AssessmentGenerationService worker;

    @Autowired
    private AssessmentGenerationRepository queue;

    @Autowired
    private PlatformTransactionManager manager;

    @MockitoBean
    private AiService ai;

    private final List<UUID> actors = new ArrayList<>();

    private UUID owner;

    private UUID job;

    private String bearer;

    @BeforeEach
    void fixture() {
        reset(ai);
        owner = actor("CANDIDATE");
        bearer = token(owner);
        job = job();
        when(ai.generateForUser(any(UUID.class), eq("ASSESSMENT_GENERATE"), anyMap()))
            .thenReturn(Map.of("questions", questions()));
    }

    @AfterEach
    void removeQueuedWork() {
        for (UUID actor : actors) {
            db.update("DELETE FROM assessment_generations WHERE user_id=?", actor);
        }
        actors.clear();
    }

    @Test
    void browserReturnsBeforeAiAndPollingSurvivesASecondStartWithoutDuplicatingWork() throws Exception {
        Map<String, Object> initial = start(job, bearer, 202);
        UUID id = UUID.fromString(initial.get("id").toString());
        assertEquals("QUEUED", initial.get("status"));
        assertFalse(initial.containsKey("lease_token"));
        verifyNoInteractions(ai);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap())).thenAnswer(call -> {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Map<?, ?> input = call.getArgument(2);
            assertEquals(JD, input.get("jd_text"));
            assertFalse(((Map<?, ?>) input.get("job")).containsKey("raw_content"));
            running.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Map.of("questions", questions());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var processing = executor.submit(worker::processNext);
            try {
                assertTrue(running.await(10, TimeUnit.SECONDS));
                mvc.perform(get(PATH + "/" + id).header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RUNNING"));
                assertEquals(id.toString(), start(job, bearer, 202).get("id"));
                mvc.perform(get(PATH).param("jobId", job.toString()).header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
                String stranger = token(actor("CANDIDATE"));
                mvc.perform(get(PATH + "/" + id).header("Authorization", stranger)).andExpect(status().isNotFound());
                assertFalse(worker.processNext());
            } finally {
                release.countDown();
            }
            assertTrue(processing.get(10, TimeUnit.SECONDS));
        }
        Map<String, Object> completed = read(id, bearer);
        assertEquals("SUCCEEDED", completed.get("status"));
        String assessment = completed.get("assessment_id").toString();
        mvc.perform(get("/api/v1/assessments/" + assessment).header("Authorization", bearer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.questions.length()").value(10))
            .andExpect(jsonPath("$.questions[0].correct_option").doesNotExist())
            .andExpect(jsonPath("$.questions[0].explanation").doesNotExist());
        verify(ai, times(1)).generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    void failedAiLeavesThePreviousAssessmentUntouchedAndNeverAutomaticallyRetries(
        int code)
        throws Exception {
        UUID previous = db.insert(
            "assessment_sessions",
            fields("user_id", owner, "market_job_id", job, "target_job_title", "Java Engineer")
        );
        when(ai.generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap()))
            .thenThrow(new ApiException(code, "provider details must not leak"));
        UUID id = UUID.fromString(start(job, bearer, 202).get("id").toString());
        assertTrue(worker.processNext());
        Map<String, Object> failed = read(id, bearer);
        assertEquals("FAILED", failed.get("status"));
        assertNull(failed.get("assessment_id"));
        assertFalse(failed.get("message").toString().contains("provider details"));
        if (code == 504) {
            assertEquals("AI_TIMEOUT", failed.get("error_code"));
        }
        assertEquals(true, db.scalar("SELECT is_current FROM assessment_sessions WHERE id=?", previous));
        assertEquals(
            1L,
            ((Number) db.scalar("SELECT count(*) FROM assessment_sessions WHERE user_id=?", owner)).longValue()
        );
        assertFalse(worker.processNext());
        verify(ai, times(1)).generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap());
    }

    @Test
    void interruptedDurableClaimsFailAndManualRetryCreatesANewRecoverableRequest() throws Exception {
        UUID id = UUID.fromString(start(job, bearer, 202).get("id").toString());
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            queue.lockQueue();
            assertTrue(queue.claimNext(UUID.randomUUID(), 1800).isPresent());
        });
        db.update("UPDATE assessment_generations SET lease_until=now()-interval '1 second' WHERE id=?", id);
        assertFalse(worker.processNext());
        assertEquals("INTERRUPTED", read(id, bearer).get("error_code"));
        verifyNoInteractions(ai);
        Map<String, Object> retried = start(job, bearer, 202);
        assertNotEquals(id.toString(), retried.get("id"));
        mvc.perform(get(PATH).param("jobId", job.toString()).header("Authorization", bearer)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(retried.get("id")));
        assertTrue(worker.processNext());
        assertEquals("SUCCEEDED", read(UUID.fromString(retried.get("id").toString()), bearer).get("status"));
    }

    @Test
    void invalidJdAndNonCandidateCannotEnqueueAndPerOwnerQueueIsBounded() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        start(job, token(actor("ADMIN")), 403);
        UUID noSkills = job();
        db.update("DELETE FROM market_job_skills WHERE market_job_id=?", noSkills);
        start(noSkills, bearer, 409);
        start(job, bearer, 202);
        start(job(), bearer, 202);
        start(job(), bearer, 202);
        start(job(), bearer, 429);
        assertEquals(3L, queue.activeCount(owner));
        verifyNoInteractions(ai);
    }

    @Test
    void revokedAccountsAndInvalidGeneratedQuestionsCannotPublishPartialAssessments() throws Exception {
        UUID id = UUID.fromString(start(job, bearer, 202).get("id").toString());
        db.update("UPDATE users SET status='SUSPENDED' WHERE id=?", owner);
        assertTrue(worker.processNext());
        assertEquals("ACCOUNT_UNAVAILABLE", queue.status(id, owner).get("error_code"));
        verifyNoInteractions(ai);
        db.update("UPDATE users SET status='ACTIVE' WHERE id=?", owner);
        UUID invalid = UUID.fromString(start(job, bearer, 202).get("id").toString());
        when(ai.generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap()))
            .thenReturn(Map.of("questions", List.of()));
        assertTrue(worker.processNext());
        assertEquals("AI_RESULT_INVALID", queue.status(invalid, owner).get("error_code"));
        assertEquals(
            0L,
            ((Number) db.scalar("SELECT count(*) FROM assessment_sessions WHERE user_id=?", owner)).longValue()
        );
    }

    @Test
    void expiredWorkerCannotPublishAfterTheBrowserExplicitlyRetries() throws Exception {
        UUID stale = UUID.fromString(start(job, bearer, 202).get("id").toString());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(ai.generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                running.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return Map.of("questions", questions());
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var oldWorker = executor.submit(worker::processNext);
            try {
                assertTrue(running.await(10, TimeUnit.SECONDS));
                db.update("UPDATE assessment_generations SET lease_until=now()-interval '1 second' WHERE id=?", stale);
                UUID fresh = UUID.fromString(start(job, bearer, 202).get("id").toString());
                assertNotEquals(stale, fresh);
                assertTrue(worker.processNext());
                assertEquals("SUCCEEDED", read(fresh, bearer).get("status"));
            } finally {
                release.countDown();
            }
            assertTrue(oldWorker.get(10, TimeUnit.SECONDS));
        }
        assertEquals("INTERRUPTED", read(stale, bearer).get("error_code"));
        assertEquals(
            1L,
            ((Number) db.scalar("SELECT count(*) FROM assessment_sessions WHERE user_id=?", owner)).longValue()
        );
    }

    @Test
    void promptKeepsEverySkillAndUsefulSectionContextWithoutDuplicatingFullJobsOrPrivateProfiles() throws Exception {
        db.update(
            "UPDATE market_job_posts SET raw_content=?,parsed_requirements=?,parsed_responsibilities=? WHERE id=?",
            JD + "x".repeat(30000),
            "Use Java safely for database transactions.",
            "Maintain reliable Java backend APIs.",
            job
        );
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title,years_experience) VALUES(?,?,?)",
            owner,
            "Java Engineer",
            2
        );
        when(ai.generateForUser(eq(owner), eq("ASSESSMENT_GENERATE"), anyMap())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(2);
            String text = input.get("jd_text").toString();
            assertTrue(text.length() <= 12000);
            assertTrue(text.contains("Use Java safely for database transactions."));
            assertTrue(text.contains("Maintain reliable Java backend APIs."));
            assertEquals(2, ((Map<?, ?>) input.get("job")).size());
            assertEquals(2, ((Map<?, ?>) input.get("profile")).size());
            assertEquals(true, input.get("jd_text_is_excerpt"));
            assertEquals(1, ((List<?>) input.get("jd_skills")).size());
            return Map.of("questions", questions());
        });
        UUID id = UUID.fromString(start(job, bearer, 202).get("id").toString());
        assertTrue(worker.processNext());
        assertEquals("SUCCEEDED", read(id, bearer).get("status"));
    }

    private UUID actor(
        String role) {
        UUID id = db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@assessment-generation.test",
                "full_name",
                "Generation Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
        actors.add(id);
        return id;
    }

    private String token(
        UUID actor) {
        return "Bearer " + tokens.issue(actor).get("accessToken");
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
                "Java Engineer",
                "role_category",
                "Backend",
                "raw_content",
                JD
            )
        );
        db.insert("market_job_skills", fields("market_job_id", id, "skill_name", "Java", "normalized_skill", "java"));
        return id;
    }

    private Map<String, Object> start(
        UUID selectedJob,
        String authorization,
        int expected)
        throws Exception {
        var response = mvc.perform(
            post(PATH).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("jobId", selectedJob)))
        ).andExpect(status().is(expected)).andReturn();
        return mapper.readValue(response.getResponse().getContentAsString(), Map.class);
    }

    private Map<String, Object> read(
        UUID id,
        String authorization)
        throws Exception {
        var response = mvc.perform(get(PATH + "/" + id).header("Authorization", authorization))
            .andExpect(status().isOk()).andReturn();
        return mapper.readValue(response.getResponse().getContentAsString(), Map.class);
    }

    private List<Map<String, Object>> questions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            result.add(
                fields(
                    "part",
                    index < 5 ? 1 : 2,
                    "question_text",
                    "How should Java requirement " + index + " be implemented?",
                    "options",
                    Map.of("A", "Validate transactions", "B", "Ignore input", "C", "Skip tests", "D", "Lose data"),
                    "correct_option",
                    "A",
                    "related_skill_name",
                    "Java",
                    "jd_evidence",
                    "Build Java backend services",
                    "explanation",
                    "The selected JD requires safe Java services."
                )
            );
        }
        return result;
    }
}
