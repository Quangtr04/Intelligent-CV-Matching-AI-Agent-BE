package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.entity.shared.learning.Skill;
import com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume.ResumeSkillId;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.jpa.UserJpaRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.learning.jpa.SkillJpaRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa.ResumeJpaRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.jpa.ResumeSkillJpaRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Runs only against an explicitly supplied disposable PostgreSQL test database.
 */
@SpringBootTest(
    properties = {
        "spring.datasource.url=${EDUNEXUS_TEST_DATABASE_URL}",
        "spring.datasource.username=${EDUNEXUS_TEST_DATABASE_USER:edunexus_test}",
        "spring.datasource.password=${EDUNEXUS_TEST_DATABASE_PASSWORD:}",
        "spring.flyway.enabled=${EDUNEXUS_TEST_FLYWAY:false}",
        "app.auth.expose-development-code=true",
        "app.payment.webhook-secret=integration-test-secret-with-32-bytes-minimum",
        "app.payment.provider=MANUAL_GATEWAY",
        "app.storage.path=target/integration-uploads"}
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "EDUNEXUS_TEST_DATABASE_URL", matches = ".+")
class CoreFlowIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    SqlStore db;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TokenService tokens;

    @Autowired
    EntitlementService entitlement;

    @Autowired
    JsonMapper mapper;

    @Autowired
    UserJpaRepository userEntities;

    @Autowired
    SkillJpaRepository skillEntities;

    @Autowired
    ResumeJpaRepository resumeEntities;

    @Autowired
    ResumeSkillJpaRepository resumeSkillEntities;

    @MockitoBean
    AiService ai;

    private String candidate, other, admin, consultant;

    private UUID candidateId, consultantId;

    @BeforeEach
    void fixtures() {
        reset(ai);
        when(ai.enabled()).thenReturn(false);
        when(ai.provider()).thenReturn("test");
        when(ai.model()).thenReturn("test-model");
        when(ai.embeddingModel()).thenReturn("test-embedding");
        candidateId = createUser("CANDIDATE");
        candidate = access(candidateId);
        other = access(createUser("CANDIDATE"));
        admin = access(createUser("ADMIN"));
        consultantId = createUser("CAREER_CONSULTANT");
        consultant = access(consultantId);
    }

    private UUID createUser(
        String role) {
        return db.insert(
            "users",
            Map.of(
                "email",
                UUID.randomUUID() + "@test.invalid",
                "full_name",
                "Integration User",
                "password_hash",
                encoder.encode("integration-secret"),
                "status",
                "ACTIVE",
                "role",
                role,
                "auth_provider",
                "EMAIL"
            )
        );
    }

    private String access(
        UUID user) {
        return tokens.issue(user).get("accessToken").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(
        org.springframework.test.web.servlet.ResultActions action)
        throws Exception {
        return mapper
            .readValue(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class);
    }

    private String json(
        Object value) {
        return mapper.writeValueAsString(value);
    }

    private Map<String, Object> postJson(
        String path,
        String token,
        Object body)
        throws Exception {
        return response(
            mvc.perform(
                post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content(json(body))
            )
        );
    }

    private UUID confirmedCv() throws Exception {
        var cv = postJson(
            "/api/v1/resumes",
            candidate,
            Map.of(
                "title",
                "Java CV",
                "structured_data",
                Map.of("full_name", "Test Candidate", "summary", "Java SQL developer", "skills", List.of("Java", "SQL"))
            )
        );
        UUID id = UUID.fromString(cv.get("id").toString());
        postJson("/api/v1/resumes/" + id + "/confirm", candidate, Map.of("is_primary", true));
        return id;
    }

    private UUID importedJob() throws Exception {
        var job = postJson(
            "/api/v1/admin/jobs",
            admin,
            Map.of(
                "sourceSite",
                "ITviec",
                "sourceUrl",
                "https://itviec.com/it-jobs/" + UUID.randomUUID(),
                "jobTitle",
                "Java Developer",
                "roleCategory",
                "Backend",
                "content",
                ("We are looking for a Java developer with SQL database experience to " + "build backend services."),
                "skills",
                List.of(Map.of("name", "Java", "required", true), Map.of("name", "SQL", "required", true))
            )
        );
        return UUID.fromString(job.get("id").toString());
    }

    @Test
    void registrationVerificationAndRefreshRotation() throws Exception {
        String email = UUID.randomUUID() + "@test.invalid";
        var registration = response(
            mvc.perform(
                post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("email", email, "password", "strong-test-password", "fullName", "Candidate")))
            )
        );
        String code = registration.get("developmentCode").toString();
        mvc.perform(
            post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", "strong-test-password")))
        ).andExpect(status().isForbidden());
        String wrong = code.equals("000000") ? "000001" : "000000";
        mvc.perform(
            post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "code", wrong)))
        ).andExpect(status().isBadRequest());
        assertEquals(
            1,
            ((Number) db.scalar(
                ("SELECT attempt_count FROM email_verification_codes c JOIN users u ON "
                    + "u.id=c.user_id WHERE u.email=? ORDER BY c.created_at DESC LIMIT 1"),
                email
            )).intValue()
        );
        response(
            mvc.perform(
                post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("email", email, "code", code)))
            )
        );
        var login = response(
            mvc.perform(
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("email", email, "password", "strong-test-password")))
            )
        );
        String refresh = login.get("refreshToken").toString();
        response(
            mvc.perform(
                post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("refreshToken", refresh)))
            )
        );
        mvc.perform(
            post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refresh)))
        ).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + refresh))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void cvReviewOwnershipAndMatchingCacheInvalidation() throws Exception {
        UUID cv = confirmedCv(), job = importedJob();
        mvc.perform(get("/api/v1/resumes/" + cv).header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());
        var matched = postJson(
            "/api/v1/matching",
            candidate,
            Map.of("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 100)
        );
        assertEquals(false, matched.get("cached"));
        var entries = (List<?>) matched.get("results");
        var found = entries.stream().map(e -> (Map<?, ?>) e)
            .filter(e -> job.toString().equals(e.get("market_job_id").toString())).findFirst().orElseThrow();
        assertEquals(1.0, ((Number) found.get("final_score")).doubleValue());
        assertNull(found.get("vector_similarity_score"));
        var cached = postJson(
            "/api/v1/matching",
            candidate,
            Map.of("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 100)
        );
        assertEquals(true, cached.get("cached"));
        response(
            mvc.perform(
                patch("/api/v1/resumes/" + cv).header("Authorization", "Bearer " + candidate)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("structured_data", Map.of("summary", "SQL only", "skills", List.of("SQL")))))
            )
        );
        mvc.perform(
            post("/api/v1/matching").header("Authorization", "Bearer " + candidate)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 10)))
        ).andExpect(status().isConflict());
        postJson("/api/v1/resumes/" + cv + "/confirm", candidate, Map.of());
        var fresh = postJson(
            "/api/v1/matching",
            candidate,
            Map.of("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 100)
        );
        assertNotEquals(matched.get("id"), fresh.get("id"));
    }

    @Test
    void assessmentHidesAnswersRequiresCompletionAndPersistsScores() throws Exception {
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "questions",
                List.of(
                    Map.of(
                        "part",
                        1,
                        "question_text",
                        "Foundation",
                        "options",
                        Map.of("A", "Correct", "B", "B", "C", "C", "D", "D"),
                        "correct_option",
                        "A",
                        "related_skill_name",
                        "General"
                    ),
                    Map.of(
                        "part",
                        2,
                        "question_text",
                        "Java",
                        "options",
                        Map.of("A", "A", "B", "Correct", "C", "C", "D", "D"),
                        "correct_option",
                        "B",
                        "related_skill_name",
                        "Java"
                    )
                )
            )
        );
        var assessment = postJson("/api/v1/assessments", candidate, Map.of("targetJobTitle", "Java Developer"));
        UUID id = UUID.fromString(assessment.get("id").toString());
        var questions = (List<?>) assessment.get("questions");
        assertFalse(((Map<?, ?>) questions.getFirst()).containsKey("correct_option"));
        mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", "Bearer " + candidate))
            .andExpect(status().isBadRequest());
        for (int i = 0; i < 2; i++)
            response(
                mvc.perform(
                    put("/api/v1/assessments/" + id + "/answers").header("Authorization", "Bearer " + candidate)
                        .contentType(MediaType.APPLICATION_JSON).content(
                            json(
                                Map.of(
                                    "questionId",
                                    ((Map<?, ?>) questions.get(i)).get("id"),
                                    "selectedOption",
                                    i == 0 ? "A" : "B"
                                )
                            )
                        )
                )
            );
        var completed = response(
            mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", "Bearer " + candidate))
        );
        assertEquals("completed", completed.get("status"));
        assertEquals(2, ((List<?>) completed.get("skill_scores")).size());
        response(
            mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", "Bearer " + candidate))
        );
        mvc.perform(get("/api/v1/assessments/" + id).header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());
    }

    @Test
    void roadmapEnforcesPrerequisitesAndComputesProgress() throws Exception {
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "title",
                "Backend learning",
                "summary",
                "Learn Java then Spring",
                "skill_gaps",
                List.of(
                    Map.of(
                        "skill_name",
                        "Java",
                        "gap_status",
                        "MISSING",
                        "current_level",
                        "beginner",
                        "target_level",
                        "intermediate",
                        "urgency_score",
                        80,
                        "reasoning",
                        "Target role requires Java"
                    )
                ),
                "nodes",
                List.of(
                    Map.of(
                        "skill_name",
                        "Java",
                        "description",
                        "Java basics",
                        "estimated_hours",
                        10,
                        "prerequisites",
                        List.of()
                    ),
                    Map.of(
                        "skill_name",
                        "Spring Boot",
                        "description",
                        "Build an API",
                        "estimated_hours",
                        20,
                        "prerequisites",
                        List.of(1)
                    )
                )
            )
        );
        var roadmap = postJson("/api/v1/roadmaps", candidate, Map.of("targetJobTitle", "Java Developer"));
        String id = roadmap.get("id").toString();
        var nodes = (List<?>) roadmap.get("nodes");
        String first = ((Map<?, ?>) nodes.getFirst()).get("id").toString(),
            second = ((Map<?, ?>) nodes.get(1)).get("id").toString();
        mvc.perform(
            patch("/api/v1/roadmaps/" + id + "/nodes/" + second).header("Authorization", "Bearer " + candidate)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "completed")))
        ).andExpect(status().isConflict());
        for (String node : List.of(first, second))
            response(
                mvc.perform(
                    patch("/api/v1/roadmaps/" + id + "/nodes/" + node).header("Authorization", "Bearer " + candidate)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "completed")))
                )
            );
        var done = response(mvc.perform(get("/api/v1/roadmaps/" + id).header("Authorization", "Bearer " + candidate)));
        assertEquals(100, ((Number) done.get("progress_percent")).intValue());
        assertEquals("completed", done.get("status"));
        mvc.perform(
            patch("/api/v1/roadmaps/" + id + "/nodes/" + first).header("Authorization", "Bearer " + candidate)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "not_started")))
        ).andExpect(status().isConflict());
    }

    @Test
    void paymentSignatureAmountValidationAndReplayAreSafe() throws Exception {
        UUID tier = (UUID) db.scalar("SELECT id FROM subscription_tiers WHERE tier_code='BASIC'");
        var order = postJson("/api/v1/payments/orders", candidate, Map.of("tierId", tier, "durationMonths", 1));
        String id = order.get("id").toString();
        var event = new LinkedHashMap<String, Object>();
        event.put("orderId", id);
        event.put("providerOrderId", order.get("provider_order_id"));
        event.put("eventId", UUID.randomUUID().toString());
        event.put("status", "COMPLETED");
        event.put("currency", order.get("currency"));
        event.put("amount", BigDecimal.ONE);
        String time = Long.toString(Instant.now().getEpochSecond());
        String wrongBody = json(event);
        mvc.perform(
            post("/api/v1/payments/webhook").header("X-Payment-Timestamp", time)
                .header("X-Payment-Signature", sign(time, wrongBody)).contentType(MediaType.APPLICATION_JSON)
                .content(wrongBody)
        ).andExpect(status().isBadRequest());
        assertEquals("PENDING", db.scalar("SELECT status FROM payment_orders WHERE id=?", UUID.fromString(id)));
        event.put("amount", order.get("amount"));
        String body = json(event);
        mvc.perform(
            post("/api/v1/payments/webhook").header("X-Payment-Timestamp", time)
                .header("X-Payment-Signature", "0".repeat(64)).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isUnauthorized());
        var completed = response(
            mvc.perform(
                post("/api/v1/payments/webhook").header("X-Payment-Timestamp", time)
                    .header("X-Payment-Signature", sign(time, body)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
        );
        assertEquals(false, completed.get("duplicate"));
        var duplicate = response(
            mvc.perform(
                post("/api/v1/payments/webhook").header("X-Payment-Timestamp", time)
                    .header("X-Payment-Signature", sign(time, body)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
        );
        assertEquals(true, duplicate.get("duplicate"));
        assertEquals(
            1,
            ((Number) db.scalar("SELECT count(*) FROM invoices WHERE payment_order_id=?", UUID.fromString(id)))
                .intValue()
        );
        assertEquals(
            1,
            ((Number) db
                .scalar("SELECT count(*) FROM user_subscriptions WHERE user_id=? AND status='ACTIVE'", candidateId))
                .intValue()
        );
    }

    @Test
    void consultationAssignmentAndConversationPrivacy() throws Exception {
        confirmedCv();
        var thread = postJson(
            "/api/v1/consultations",
            candidate,
            Map.of("subject", "Career guidance", "description", "Please review my target career")
        );
        String id = thread.get("id").toString();
        mvc.perform(get("/api/v1/consultations/" + id + "/messages").header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/consultations/" + id).header("Authorization", "Bearer " + consultant))
            .andExpect(status().isNotFound());
        response(
            mvc.perform(
                put("/api/v1/consultations/" + id + "/consultant").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("consultantId", consultantId)))
            )
        );
        var candidateData = response(
            mvc.perform(
                get("/api/v1/consultations/" + id + "/candidate-profile")
                    .header("Authorization", "Bearer " + consultant)
            )
        );
        assertFalse(((Map<?, ?>) candidateData.get("resume")).isEmpty());
        mvc.perform(
            get("/api/v1/consultations/" + id + "/candidate-profile").header("Authorization", "Bearer " + other)
        ).andExpect(status().isNotFound());
        postJson("/api/v1/consultations/" + id + "/messages", candidate, Map.of("content", "Hello"));
        postJson("/api/v1/consultations/" + id + "/messages", consultant, Map.of("content", "Here is my advice"));
        response(
            mvc.perform(post("/api/v1/consultations/" + id + "/close").header("Authorization", "Bearer " + candidate))
        );
        mvc.perform(
            post("/api/v1/consultations/" + id + "/messages").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("content", "Closed thread")))
        ).andExpect(status().isConflict());
    }

    @Test
    void atsCacheDraftsAndAssistantKeepOwnedContext() throws Exception {
        UUID cv = confirmedCv(), job = importedJob();
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(
            Map.of(
                "ats_score",
                80,
                "strengths",
                List.of("Java"),
                "missing_skills",
                List.of(),
                "improvement_suggestions",
                List.of("Add project outcomes")
            )
        );
        var first = postJson("/api/v1/jobs/" + job + "/ats", candidate, Map.of("resumeId", cv));
        assertEquals(false, first.get("cached"));
        var cached = postJson("/api/v1/jobs/" + job + "/ats", candidate, Map.of("resumeId", cv));
        assertEquals(true, cached.get("cached"));
        verify(ai, times(1)).generate(eq("JOB_ATS"), anyMap());
        mvc.perform(
            post("/api/v1/jobs/" + job + "/ats").header("Authorization", "Bearer " + other)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", cv)))
        ).andExpect(status().isNotFound());
        when(ai.generate(eq("APPLICATION_EMAIL"), anyMap())).thenReturn(
            Map.of(
                "subject",
                "Application",
                "email",
                "I would like to apply for this position.",
                "cover_letter",
                "My experience includes Java and SQL."
            )
        );
        var draftsResult = mvc.perform(
            post("/api/v1/jobs/" + job + "/application-drafts").header("Authorization", "Bearer " + candidate)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", cv, "language", "en")))
        ).andExpect(status().isOk()).andReturn();
        assertEquals(2, mapper.readValue(draftsResult.getResponse().getContentAsString(), List.class).size());
        when(ai.generate(eq("CAREER_CHAT"), anyMap())).thenReturn(
            Map.of(
                "answer",
                "Practice Java by building an API.",
                "_model",
                "test-model",
                "_usage",
                Map.of("input_tokens", 10, "output_tokens", 10)
            )
        );
        var conversation =
            postJson("/api/v1/assistant/conversations", candidate, Map.of("title", "Career", "related_resume_id", cv));
        String id = conversation.get("id").toString();
        var reply = postJson(
            "/api/v1/assistant/conversations/" + id + "/messages",
            candidate,
            Map.of("message", "How should I prepare?")
        );
        assertEquals(List.of(), reply.get("sources"));
        mvc.perform(get("/api/v1/assistant/conversations/" + id).header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/assistant/conversations/" + id).header("Authorization", "Bearer " + candidate))
            .andExpect(status().isNoContent());
        mvc.perform(
            post("/api/v1/assistant/conversations/" + id + "/messages").header("Authorization", "Bearer " + candidate)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("message", "Closed")))
        ).andExpect(status().isConflict());
    }

    @Test
    void guestDemoQuotaAndTargetedAnnouncementsAreIsolated() throws Exception {
        importedJob();
        var session = response(mvc.perform(post("/api/v1/guest/sessions")));
        String key = session.get("sessionKey").toString();
        UUID sessionId = UUID.fromString(session.get("id").toString());
        UUID demo =
            (UUID) db.scalar("SELECT id FROM resumes WHERE is_demo AND user_id IS NULL AND status='READY' LIMIT 1");
        var trial = response(
            mvc.perform(
                post("/api/v1/guest/trials/recommendations").header("X-Guest-Session", key)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("demo_resume_id", demo)))
            )
        );
        assertEquals("SKILL_OVERLAP", trial.get("mode"));
        assertEquals(
            100,
            ((Number) db.scalar("SELECT token_used FROM guest_sessions WHERE id=?", sessionId)).intValue()
        );
        db.update("UPDATE guest_sessions SET token_limit=100 WHERE id=?", sessionId);
        mvc.perform(
            post("/api/v1/guest/trials/recommendations").header("X-Guest-Session", key)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("demo_resume_id", demo)))
        ).andExpect(status().isTooManyRequests());
        var announcement = postJson(
            "/api/v1/admin/announcements",
            admin,
            Map.of(
                "title",
                "Targeted notice",
                "content",
                "Only this candidate receives this notice",
                "targets",
                List.of(Map.of("type", "USER", "userId", candidateId))
            )
        );
        String id = announcement.get("id").toString();
        postJson("/api/v1/admin/announcements/" + id + "/publish", admin, Map.of());
        String ownAnnouncements =
            mvc.perform(get("/api/v1/announcements").header("Authorization", "Bearer " + candidate))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(ownAnnouncements.contains(id));
        String otherAnnouncements = mvc.perform(get("/api/v1/announcements").header("Authorization", "Bearer " + other))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(otherAnnouncements.contains(id));
        mvc.perform(get("/api/v1/admin/dashboard").header("Authorization", "Bearer " + candidate))
            .andExpect(status().isForbidden());
        var ticket = postJson(
            "/api/v1/support/tickets",
            candidate,
            Map.of("subject", "Help", "category", "GENERAL", "description", "Please help with my profile")
        );
        String ticketId = ticket.get("id").toString();
        mvc.perform(get("/api/v1/support/tickets/" + ticketId).header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());
        postJson("/api/v1/support/tickets/" + ticketId + "/messages", admin, Map.of("content", "Support is available"));
    }

    @Test
    void concurrentReservationsCannotOverspendMonthlyQuota() throws Exception {
        List<Object> results = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Object> reserve = () -> {
                try {
                    return entitlement.reserve(candidateId, "CV_BUILD", 16000);
                } catch (ApiException ex) {
                    return ex.status();
                }
            };
            var futures = executor.invokeAll(List.of(reserve, reserve));
            for (var future : futures)
                results.add(future.get());
        }
        assertEquals(1, results.stream().filter(UUID.class::isInstance).count());
        assertTrue(results.contains(429));
        UUID reservation = (UUID) results.stream().filter(UUID.class::isInstance).findFirst().orElseThrow();
        entitlement.complete(reservation, 4000);
        UUID another = entitlement.reserve(candidateId, "CV_BUILD", 16000);
        entitlement.cancel(another);
        assertEquals(4000L, ((Number) entitlement.summary(candidateId).get("tokensUsedThisMonth")).longValue());
    }

    private static String sign(
        String time,
        String body)
        throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
            new SecretKeySpec(
                "integration-test-secret-with-32-bytes-minimum".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
        );
        return HexFormat.of().formatHex(mac.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void openApiExposesBearerAndGuestHeaderSchemes() throws Exception {
        var api = response(mvc.perform(get("/v3/api-docs")));
        var components = (Map<?, ?>) api.get("components");
        var schemes = (Map<?, ?>) components.get("securitySchemes");
        assertTrue(schemes.containsKey("bearerAuth"));
        assertTrue(schemes.containsKey("guestSession"));
        var paths = (Map<?, ?>) api.get("paths");
        assertTrue(paths.containsKey("/api/v1/resumes/{id}/confirm"));
        assertTrue(paths.containsKey("/api/v1/payments/webhook"));
    }

    @Test
    void typedEntityRepositoriesReadJsonCompositeKeysAndGeneratedUuids() throws Exception {
        var account = userEntities.findById(candidateId).orElseThrow();
        assertEquals("CANDIDATE", account.getRole());
        assertNotNull(account.getCreatedAt());
        assertFalse(json(account).contains("password_hash"));
        var profile = response(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + candidate)));
        assertEquals(candidateId.toString(), profile.get("id"));
        assertEquals("Integration User", profile.get("full_name"));
        assertFalse(profile.containsKey("password_hash"));
        Skill skill = new Skill();
        skill.setName("Integration Skill " + UUID.randomUUID());
        skill.setSlug("integration-" + UUID.randomUUID());
        skill.setIsActive(true);
        var saved = skillEntities.saveAndFlush(skill);
        assertNotNull(saved.getId());
        assertEquals(saved.getName(), skillEntities.findById(saved.getId()).orElseThrow().getName());
        UUID cv = confirmedCv();
        var resume = resumeEntities.findById(cv).orElseThrow();
        assertEquals("READY", resume.getStatus());
        assertTrue(resume.getStructuredData() instanceof Map<?, ?>);
        db.update("INSERT INTO resume_skills(resume_id,skill_id) VALUES(?,?)", cv, saved.getId());
        var linked = resumeSkillEntities.findById(new ResumeSkillId(cv, saved.getId())).orElseThrow();
        assertEquals(saved.getId(), linked.getSkillId());
        assertEquals(cv, linked.getResumeId());
    }
}
