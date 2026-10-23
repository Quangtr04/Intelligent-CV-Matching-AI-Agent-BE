package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ProfileRepository;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeProfileAlignmentRefreshService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed fixtures exercise the asynchronous worker independently of request-bound transactions. */
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
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ResumeProfileAlignmentRefreshIntegrationTests {

    private static final String PROFILE = "/api/v1/me/career-profile";

    private static final String FEATURE = "CV_PROFILE_ALIGNMENT";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private ResumeProfileAlignmentRefreshService worker;

    @Autowired
    private ProfileRepository profiles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AiService ai;

    private UUID owner;

    private UUID resume;

    private String candidate;

    @BeforeEach
    void fixture() {
        reset(ai);
        owner = user("CANDIDATE");
        candidate = token(owner);
        profile(owner);
        resume = resume(owner, "READY");
        when(ai.enabled()).thenReturn(true);
        when(ai.model()).thenReturn("refresh-test-model");
        when(ai.generate(eq(FEATURE), anyMap())).thenAnswer(ignored -> generated());
        when(ai.generateForUser(any(UUID.class), eq(FEATURE), anyMap())).thenAnswer(ignored -> generated());
    }

    @AfterEach
    void removePendingFixtureWork() {
        if (owner != null) {
            db.update("DELETE FROM resume_profile_alignment_refreshes WHERE user_id=?", owner);
        }
    }

    @Test
    void profileSaveQueuesAnExistingComparisonWithoutCallingAiOrChangingAtsHistory() throws Exception {
        Map<String, Object> old = create(resume, candidate);
        UUID ats = db.insert(
            "resume_analyses",
            Map.of(
                "resume_id",
                resume,
                "analysis_type",
                "CV_ANALYZE",
                "overall_score",
                81,
                "ats_score",
                73,
                "summary",
                "Independent ATS assessment",
                "model_name",
                "ats-test-model"
            )
        );
        Map<String, Object> originalAts = db.one("SELECT * FROM resume_analyses WHERE id=?", ats);
        Map<String, Object> originalAlignment =
            db.one("SELECT * FROM resume_profile_alignments WHERE id=?", UUID.fromString(old.get("id").toString()));
        clearInvocations(ai);

        updateProfile("Build maintainable backend services", 8500000);

        assertEquals("QUEUED", queue().get("status"));
        assertEquals(1, count());
        assertEquals(originalAts, db.one("SELECT * FROM resume_analyses WHERE id=?", ats));
        assertEquals(
            originalAlignment,
            db.one("SELECT * FROM resume_profile_alignments WHERE id=?", UUID.fromString(old.get("id").toString()))
        );
        mvc.perform(get(path(resume) + "/refresh-status").header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get(path(resume)).header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].profile_changed").value(true));
        mvc.perform(post(path(resume)).header("Authorization", candidate)).andExpect(status().isConflict());
        verifyNoInteractions(ai);
    }

    @Test
    void workerCreatesANewSnapshotForThePersistedOwnerAndLeavesAtsResultsUntouched() throws Exception {
        create(resume, candidate);
        UUID ats =
            db.insert("resume_analyses", Map.of("resume_id", resume, "analysis_type", "CV_ANALYZE", "ats_score", 73));
        Map<String, Object> originalAts = db.one("SELECT * FROM resume_analyses WHERE id=?", ats);
        updateProfile("Become a senior Java developer", 9000000);
        clearInvocations(ai);

        assertTrue(worker.processNext());

        Map<String, Object> task = queue();
        assertEquals("SUCCEEDED", task.get("status"));
        assertNotNull(task.get("alignment_id"));
        assertEquals(2, count());
        mvc.perform(get(path(resume) + "/" + task.get("alignment_id")).header("Authorization", candidate))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile_snapshot.career_goal").value("Become a senior Java developer"))
            .andExpect(jsonPath("$.profile_snapshot.expected_salary").value(9000000))
            .andExpect(jsonPath("$.is_stale").value(false));
        verify(ai).generateForUser(eq(owner), eq(FEATURE), argThat(input -> {
            Map<?, ?> saved = (Map<?, ?>) input.get("career_profile");
            return "Become a senior Java developer".equals(saved.get("career_goal"));
        }));
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(originalAts, db.one("SELECT * FROM resume_analyses WHERE id=?", ats));
        assertFalse(worker.processNext());
    }

    @Test
    void aCanonicallyIdenticalSaveDoesNotQueueOrConsumeInference() throws Exception {
        create(resume, candidate);
        clearInvocations(ai);

        updateProfile(null, 7500000);

        assertEquals(0, queueCount(owner));
        assertEquals(1, count());
        assertFalse(worker.processNext());
        verifyNoInteractions(ai);
    }

    @Test
    void onlyTheOwnersReadyPreviouslyComparedCvIsQueued() throws Exception {
        create(resume, candidate);
        UUID neverCompared = resume(owner, "READY");
        UUID draft = resume(owner, "READY");
        create(draft, candidate);
        db.update("UPDATE resumes SET status='DRAFT' WHERE id=?", draft);
        UUID otherOwner = user("CANDIDATE");
        profile(otherOwner);
        UUID anotherCv = resume(otherOwner, "READY");
        create(anotherCv, token(otherOwner));
        clearInvocations(ai);

        updateProfile("Focus on Spring applications", 7500000);

        assertEquals(1, queueCount(owner));
        assertEquals(0, queueCount(otherOwner));
        assertEquals(resume, queue().get("resume_id"));
        assertEquals(
            0L,
            db.scalar(
                "SELECT count(*) FROM resume_profile_alignment_refreshes WHERE resume_id IN (?,?)",
                neverCompared,
                draft
            )
        );
        verifyNoInteractions(ai);
    }

    @Test
    void changingOnlyTheProfileSkillsSchedulesTheSameIndependentComparison() throws Exception {
        create(resume, candidate);
        UUID skill = db.insert(
            "skills",
            Map.of("name", "Refresh Skill " + UUID.randomUUID(), "slug", "refresh-skill-" + UUID.randomUUID())
        );
        Map<String, Object> body = profileBody(null, 7500000);
        body.put(
            "skills",
            List.of(
                Map.of(
                    "skillId",
                    "20000000-0000-0000-0000-000000000001",
                    "proficiencyLevel",
                    "beginner",
                    "yearsExperience",
                    0.5
                ),
                Map.of("skillId", skill.toString(), "proficiencyLevel", "beginner")
            )
        );
        clearInvocations(ai);

        saveProfile(body);

        assertEquals("QUEUED", queue().get("status"));
        verifyNoInteractions(ai);
        assertTrue(worker.processNext());
        Map<String, Object> task = queue();
        Map<?, ?> snapshot = (Map<?, ?>) db
            .one("SELECT profile_snapshot FROM resume_profile_alignments WHERE id=?", task.get("alignment_id"))
            .get("profile_snapshot");
        assertEquals(2, ((List<?>) snapshot.get("skills")).size());
    }

    @Test
    void repeatedPendingEditsCoalesceAndOnlyTheNewestProfileIsEvaluated() throws Exception {
        create(resume, candidate);
        updateProfile("Initial revised goal", 8000000);
        long firstGeneration = ((Number) queue().get("generation")).longValue();
        clearInvocations(ai);

        updateProfile("Latest revised goal", 11000000);

        assertEquals(1, queueCount(owner));
        assertTrue(((Number) queue().get("generation")).longValue() > firstGeneration);
        verifyNoInteractions(ai);
        assertTrue(worker.processNext());
        assertEquals(2, count());
        verify(ai, times(1)).generateForUser(eq(owner), eq(FEATURE), argThat(input -> {
            Map<?, ?> current = (Map<?, ?>) input.get("career_profile");
            return "Latest revised goal".equals(current.get("career_goal"));
        }));
        assertFalse(worker.processNext());
    }

    @Test
    void anEditDuringInferenceDiscardsTheSupersededResultAndQueuesTheNewGeneration() throws Exception {
        create(resume, candidate);
        updateProfile("First queued goal", 8000000);
        clearInvocations(ai);
        AtomicInteger calls = new AtomicInteger();
        when(ai.generateForUser(eq(owner), eq(FEATURE), anyMap())).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 1) {
                // HTTP requests run independently of the worker transaction, including its rollback status.
                ExecutorService requests = Executors.newSingleThreadExecutor();
                try {
                    var edited = requests.submit(() -> {
                        mvc.perform(post(path(resume)).header("Authorization", candidate))
                            .andExpect(status().isConflict());
                        updateProfile("Goal saved during inference", 12000000);
                        return null;
                    });
                    edited.get(8, TimeUnit.SECONDS);
                } finally {
                    requests.shutdownNow();
                    assertTrue(requests.awaitTermination(8, TimeUnit.SECONDS), "Concurrent requests must terminate");
                }
            }
            return generated();
        });

        assertTrue(worker.processNext());

        assertEquals(1, count());
        assertEquals("QUEUED", queue().get("status"));
        assertTrue(worker.processNext());
        assertEquals(2, count());
        assertEquals("SUCCEEDED", queue().get("status"));
        Map<?, ?> snapshot = (Map<?, ?>) db
            .one("SELECT profile_snapshot FROM resume_profile_alignments WHERE id=?", queue().get("alignment_id"))
            .get("profile_snapshot");
        assertEquals("Goal saved during inference", snapshot.get("career_goal"));
        assertEquals(2, calls.get());
        assertFalse(worker.processNext());
    }

    @Test
    void invalidModelEvidenceFailsOnceWithoutRollingBackTheSavedProfileOrOldHistory() throws Exception {
        create(resume, candidate);
        updateProfile("Keep the saved profile even if AI fails", 9500000);
        Map<String, Object> invalid = generated();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> criteria = (List<Map<String, Object>>) invalid.get("criteria");
        criteria.getFirst().put("cv_evidence", List.of("Invented production leadership experience"));
        when(ai.generateForUser(eq(owner), eq(FEATURE), anyMap())).thenReturn(invalid);
        clearInvocations(ai);

        assertTrue(worker.processNext());

        assertEquals("FAILED", queue().get("status"));
        assertNotNull(queue().get("error_code"));
        assertEquals(1, count());
        assertEquals(
            "Keep the saved profile even if AI fails",
            db.scalar("SELECT career_goal FROM candidate_profiles WHERE user_id=?", owner)
        );
        assertFalse(worker.processNext());
        verify(ai, times(1)).generateForUser(eq(owner), eq(FEATURE), anyMap());
    }

    @Test
    void refreshStatusRequiresTheCandidateOwnerWithoutInvokingAi() throws Exception {
        mvc.perform(get(path(resume) + "/refresh-status")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(resume) + "/refresh-status").header("Authorization", token(user("ADMIN"))))
            .andExpect(status().isForbidden());
        mvc.perform(get(path(resume) + "/refresh-status").header("Authorization", token(user("CANDIDATE"))))
            .andExpect(status().isNotFound());
        mvc.perform(get(path(resume) + "/refresh-status").header("Authorization", candidate)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NONE"));
        verifyNoInteractions(ai);
    }

    @Test
    void anExpiredRunningClaimIsFailedWithoutAutomaticallyRepeatingInference() throws Exception {
        create(resume, candidate);
        updateProfile("Interrupted evaluation", 8000000);
        db.update(
            "UPDATE resume_profile_alignment_refreshes SET status='RUNNING',lease_token=?,"
                + "lease_until=now()-interval '1 minute',started_at=now()-interval '2 minutes' WHERE resume_id=?",
            UUID.randomUUID(),
            resume
        );
        clearInvocations(ai);

        assertFalse(worker.processNext());

        assertEquals("FAILED", queue().get("status"));
        assertEquals("INTERRUPTED", queue().get("error_code"));
        assertEquals(1, count());
        assertFalse(worker.processNext());
        verifyNoInteractions(ai);
    }

    @Test
    void aManualSuccessAfterFailureReplacesTheFailedRefreshStatus() throws Exception {
        create(resume, candidate);
        updateProfile("Retry manually after a failed refresh", 8000000);
        when(ai.generateForUser(eq(owner), eq(FEATURE), anyMap()))
            .thenThrow(new IllegalStateException("Synthetic local provider failure"));
        assertTrue(worker.processNext());
        assertEquals("FAILED", queue().get("status"));
        clearInvocations(ai);

        Map<String, Object> saved = create(resume, candidate);

        assertEquals(2, count());
        assertEquals("SUCCEEDED", queue().get("status"));
        assertEquals(saved.get("id").toString(), queue().get("alignment_id").toString());
        assertNull(queue().get("error_code"));
        verify(ai).generate(eq(FEATURE), anyMap());
        verify(ai, never()).generateForUser(any(UUID.class), anyString(), anyMap());
        assertFalse(worker.processNext());
    }

    @Test
    void anAlreadyFreshComparisonCompletesQueuedWorkWithoutAnotherModelCall() throws Exception {
        create(resume, candidate);
        updateProfile("A temporary edited goal", 8000000);
        updateProfile(null, 7500000);
        clearInvocations(ai);

        assertTrue(worker.processNext());

        assertEquals("SUCCEEDED", queue().get("status"));
        assertNotNull(queue().get("alignment_id"));
        assertEquals(1, count());
        assertFalse(worker.processNext());
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).generateForUser(any(UUID.class), anyString(), anyMap());
    }

    @Test
    void aConcurrentProfileSaveDoesNotDeadlockReportPublicationThroughTheUserForeignKey() throws Exception {
        UUID existing = UUID.fromString(create(resume, candidate).get("id").toString());
        CountDownLatch profileLocked = new CountDownLatch(1);
        CountDownLatch ownerLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var publishing =
                executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    db.update("SET LOCAL statement_timeout = '3s'");
                    db.one("SELECT user_id FROM candidate_profiles WHERE user_id=? FOR UPDATE", owner);
                    profileLocked.countDown();
                    awaitLatch(ownerLocked, "The profile save must acquire its owner lock before publication");
                    assertEquals(
                        1,
                        db.update(
                            "INSERT INTO resume_profile_alignments(resume_id,user_id,resume_content_hash,profile_hash,"
                                + "profile_snapshot,resume_snapshot,result_json,model_name) "
                                + "SELECT resume_id,user_id,resume_content_hash,profile_hash,profile_snapshot,"
                                + "resume_snapshot,result_json,model_name FROM resume_profile_alignments WHERE id=?",
                            existing
                        )
                    );
                }));
            var saving = executor.submit(() -> {
                awaitLatch(profileLocked, "The report transaction must acquire its profile lock first");
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    db.update("SET LOCAL statement_timeout = '3s'");
                    profiles.lockCandidateProfileOwner(owner);
                    ownerLocked.countDown();
                    db.one("SELECT user_id FROM candidate_profiles WHERE user_id=? FOR UPDATE", owner);
                });
            });

            publishing.get(8, TimeUnit.SECONDS);
            saving.get(8, TimeUnit.SECONDS);
            assertEquals(2, count());
        } finally {
            profileLocked.countDown();
            ownerLocked.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(8, TimeUnit.SECONDS), "Concurrency test workers must terminate");
        }
    }

    private static void awaitLatch(
        CountDownLatch latch,
        String message) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), message);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, ex);
        }
    }

    private void updateProfile(
        String goal,
        int salary)
        throws Exception {
        saveProfile(profileBody(goal, salary));
    }

    private void saveProfile(
        Map<String, Object> body)
        throws Exception {
        mvc.perform(
            put(PROFILE).header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))
        ).andExpect(status().isOk());
    }

    private static Map<String, Object> profileBody(
        String goal,
        int salary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetJobTitle", "Java Developer");
        body.put("careerGoal", goal);
        body.put("expectedSalary", salary);
        body.put("salaryCurrency", "VND");
        return body;
    }

    private Map<String, Object> queue() {
        return db.one("SELECT * FROM resume_profile_alignment_refreshes WHERE resume_id=?", resume);
    }

    private int queueCount(
        UUID user) {
        return ((Number) db.scalar("SELECT count(*) FROM resume_profile_alignment_refreshes WHERE user_id=?", user))
            .intValue();
    }

    private int count() {
        return ((Number) db.scalar("SELECT count(*) FROM resume_profile_alignments WHERE resume_id=?", resume))
            .intValue();
    }

    private Map<String, Object> create(
        UUID cv,
        String authorization)
        throws Exception {
        return response(
            mvc.perform(post(path(cv)).header("Authorization", authorization)).andExpect(status().isOk()).andReturn()
        );
    }

    private Map<String, Object> response(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private static String path(
        UUID cv) {
        return "/api/v1/resumes/" + cv + "/profile-alignments";
    }

    private UUID user(
        String role) {
        return db.insert(
            "users",
            Map.of(
                "email",
                "alignment-refresh-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Alignment Refresh Test",
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

    private void profile(
        UUID id) {
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title,expected_salary,salary_currency)"
                + " VALUES(?,'Java Developer',7500000,'VND')",
            id
        );
        db.update(
            "INSERT INTO candidate_profile_skills(user_id,skill_id,proficiency_level,years_experience)"
                + " VALUES(?,'20000000-0000-0000-0000-000000000001','beginner',0.5)",
            id
        );
    }

    private UUID resume(
        UUID id,
        String status) {
        Map<String, Object> reviewed = ResumeData.normalize(
            Map.of("summary", "Java Developer with Java project experience", "skills", List.of(Map.of("name", "Java")))
        );
        return db.insert(
            "resumes",
            Map.of(
                "user_id",
                id,
                "title",
                "Refresh CV",
                "language",
                "en",
                "source",
                "MANUAL",
                "status",
                status,
                "raw_content",
                "Old extraction must not override reviewed content",
                "structured_data",
                db.json(reviewed),
                "content_hash",
                FlowSupport.hash("profile refresh fixture CV")
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
            row.put("status", assessed ? "ASSESSED" : "INSUFFICIENT_EVIDENCE");
            row.put("score", assessed ? code.equals("TARGET_ROLE") ? 80 : 100 : null);
            row.put(
                "feedback",
                assessed
                    ? "Both sources contain this career information."
                    : "No sufficient comparable evidence is available."
            );
            row.put("profile_evidence", assessed ? List.of(quote) : List.of());
            row.put("cv_evidence", assessed ? List.of(quote) : List.of());
            row.put("suggestions", List.of());
            criteria.add(row);
        }
        return new LinkedHashMap<>(
            Map.of("rubric_version", "cv-profile-alignment-v1", "criteria", criteria, "_model", "refresh-test-model")
        );
    }
}
