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
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

/**
 * Runs only against an explicitly supplied disposable PostgreSQL test database.
 */
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

    private UUID candidateId, consultantId, adminId;

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
        adminId = createUser("ADMIN");
        admin = access(adminId);
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
        UUID jobId = importedJob();
        List<Map<String, Object>> generated = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            generated.add(
                Map.of(
                    "part",
                    index < 5 ? 1 : 2,
                    "question_text",
                    "Job-specific question " + index,
                    "options",
                    Map.of("A", "Correct", "B", "B", "C", "C", "D", "D"),
                    "correct_option",
                    "A",
                    "related_skill_name",
                    index < 5 ? "Java" : "SQL",
                    "jd_evidence",
                    "Java developer with SQL database experience",
                    "explanation",
                    "The selected JD requires Java and SQL backend experience."
                )
            );
        }
        when(ai.generate(eq("ASSESSMENT_GENERATE"), anyMap())).thenReturn(Map.of("questions", generated));
        var assessment = postJson("/api/v1/assessments", candidate, Map.of("jobId", jobId));
        UUID id = UUID.fromString(assessment.get("id").toString());
        var questions = (List<?>) assessment.get("questions");
        assertFalse(((Map<?, ?>) questions.getFirst()).containsKey("correct_option"));
        mvc.perform(post("/api/v1/assessments/" + id + "/submit").header("Authorization", "Bearer " + candidate))
            .andExpect(status().isBadRequest());
        for (int i = 0; i < questions.size(); i++)
            response(
                mvc.perform(
                    put("/api/v1/assessments/" + id + "/answers").header("Authorization", "Bearer " + candidate)
                        .contentType(MediaType.APPLICATION_JSON).content(
                            json(Map.of("questionId", ((Map<?, ?>) questions.get(i)).get("id"), "selectedOption", "A"))
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
        UUID selectedJob = importedJob();
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "title",
                "Backend learning",
                "summary",
                "Learn Java then SQL",
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
                    ),
                    Map.of("skill_name", "SQL", "gap_status", "MISSING_EVIDENCE", "urgency_score", 70)
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
                        "SQL",
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
        var roadmap = postJson("/api/v1/roadmaps", candidate, Map.of("jobId", selectedJob));
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
        assertEquals(1, consultationNotifications(adminId, "CONSULTATION_REQUEST", id));
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
        assertEquals(1, consultationNotifications(consultantId, "CONSULTATION_ASSIGNED", id));
        assertEquals(1, consultationNotifications(candidateId, "CONSULTATION_ASSIGNED", id));
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
        postJson("/api/v1/consultations/" + id + "/messages", candidate, Map.of("content", "Are you there?"));
        postJson("/api/v1/consultations/" + id + "/messages", consultant, Map.of("content", "Here is my advice"));
        assertEquals(1, consultationNotifications(consultantId, "CONSULTATION_MESSAGE", id));
        assertEquals(1, consultationNotifications(candidateId, "CONSULTATION_MESSAGE", id));
        response(
            mvc.perform(post("/api/v1/consultations/" + id + "/close").header("Authorization", "Bearer " + candidate))
        );
        mvc.perform(
            post("/api/v1/consultations/" + id + "/messages").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("content", "Closed thread")))
        ).andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directChatGoesStraightToChosenConsultantWithInboxSummary() throws Exception {
        var consultants = (List<Map<String, Object>>) mapper.readValue(
            mvc.perform(get("/api/v1/consultations/consultants").header("Authorization", "Bearer " + candidate))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
            List.class
        );
        assertTrue(consultants.stream().anyMatch(c -> consultantId.toString().equals(c.get("id"))));

        var thread = postJson(
            "/api/v1/consultations",
            candidate,
            Map.of(
                "subject",
                "Direct chat",
                "requestType",
                "DIRECT_CHAT",
                "description",
                "Frontend or Fullstack?",
                "consultantId",
                consultantId
            )
        );
        String id = thread.get("id").toString();
        assertEquals(consultantId.toString(), thread.get("consultant_user_id").toString());
        assertEquals(1, consultationNotifications(consultantId, "CONSULTATION_ASSIGNED", id));
        assertEquals(0, consultationNotifications(adminId, "CONSULTATION_REQUEST", id));

        postJson("/api/v1/consultations/" + id + "/messages", candidate, Map.of("content", "First"));
        postJson("/api/v1/consultations/" + id + "/messages", candidate, Map.of("content", "Second"));
        postJson("/api/v1/consultations/" + id + "/messages", candidate, Map.of("content", "Third"));

        var inbox = (List<Map<String, Object>>) mapper.readValue(
            mvc.perform(get("/api/v1/consultations").header("Authorization", "Bearer " + consultant))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
            List.class
        );
        var row = inbox.stream().filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow();
        assertEquals("Third", row.get("last_message"));
        assertEquals(3, ((Number) row.get("unread_count")).intValue());
        assertNotNull(row.get("candidate_name"));

        var latest = (List<Map<String, Object>>) mapper.readValue(
            mvc.perform(
                get("/api/v1/consultations/" + id + "/messages").param("limit", "2").param("latest", "true")
                    .header("Authorization", "Bearer " + consultant)
            ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
            List.class
        );
        assertEquals(List.of("Second", "Third"), latest.stream().map(m -> m.get("content")).toList());

        var read = response(
            mvc.perform(post("/api/v1/consultations/" + id + "/read").header("Authorization", "Bearer " + consultant))
        );
        assertEquals(3, ((Number) read.get("updated")).intValue());
        mvc.perform(post("/api/v1/consultations/" + id + "/read").header("Authorization", "Bearer " + other))
            .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/consultations/" + id + "/events").param("access_token", "not-a-token"))
            .andExpect(status().isUnauthorized());
    }

    private int consultationNotifications(
        UUID userId,
        String type,
        String threadId) {
        return ((Number) db.scalar(
            "SELECT count(*) FROM notifications WHERE user_id=? AND type=? AND reference_id=?",
            userId,
            type,
            UUID.fromString(threadId)
        )).intValue();
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

        String analysesPath = "/api/v1/consultations/{id}/candidate-analyses";
        assertTrue(paths.containsKey(analysesPath));
        var analysesOperation = (Map<?, ?>) ((Map<?, ?>) paths.get(analysesPath)).get("get");
        var parameters = (List<?>) analysesOperation.get("parameters");
        Map<String, Map<?, ?>> querySchemas = new LinkedHashMap<>();
        for (Object parameter : parameters) {
            var details = (Map<?, ?>) parameter;
            if ("query".equals(details.get("in"))) {
                querySchemas.put(details.get("name").toString(), (Map<?, ?>) details.get("schema"));
            }
        }

        assertEquals(Set.of("resumeId", "jobId", "limit", "offset"), querySchemas.keySet());
        assertEquals("uuid", querySchemas.get("resumeId").get("format"));
        assertEquals("uuid", querySchemas.get("jobId").get("format"));
        var limitSchema = querySchemas.get("limit");
        assertEquals(20, ((Number) limitSchema.get("default")).intValue());
        assertEquals(1, ((Number) limitSchema.get("minimum")).intValue());
        assertEquals(100, ((Number) limitSchema.get("maximum")).intValue());
        var offsetSchema = querySchemas.get("offset");
        assertEquals(0, ((Number) offsetSchema.get("default")).intValue());
        assertEquals(0, ((Number) offsetSchema.get("minimum")).intValue());
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

    @Test
    void configurationRestoreCreatesNewActiveVersionAndPreservesTypedHistory() throws Exception {
        String configType =
            "TEST_" + UUID.randomUUID().toString().replace("-", "").replaceAll("[0-9]", "X").toUpperCase(Locale.ROOT);
        Map<String, Object> originalConfiguration = new LinkedHashMap<>();
        originalConfiguration.put("temperature", 0.2);
        originalConfiguration.put("stream", true);
        originalConfiguration.put("limits", Map.of("max_output_tokens", 512));
        originalConfiguration.put("labels", List.of("baseline", "Vietnamese\n\"prompt\""));
        originalConfiguration.put("optional", null);

        var first = postJson(
            "/api/v1/admin/ai/configurations",
            admin,
            Map.of(
                "configType",
                configType,
                "provider",
                "first-provider",
                "modelName",
                "first-model",
                "configuration",
                originalConfiguration
            )
        );
        var second = postJson(
            "/api/v1/admin/ai/configurations",
            admin,
            Map.of(
                "configType",
                configType,
                "provider",
                "second-provider",
                "modelName",
                "second-model",
                "configuration",
                Map.of("temperature", 0.8, "stream", false)
            )
        );
        var unrelated = postJson(
            "/api/v1/admin/ai/configurations",
            admin,
            Map.of(
                "configType",
                configType + "_OTHER",
                "provider",
                "other-provider",
                "modelName",
                "other-model",
                "configuration",
                Map.of("dimensions", 1024)
            )
        );
        UUID unrelatedId = UUID.fromString(unrelated.get("id").toString());
        var unrelatedBefore = db.one("SELECT * FROM ai_configurations WHERE id=?", unrelatedId);

        var restored = response(
            mvc.perform(
                post("/api/v1/admin/ai/configurations/" + first.get("id") + "/restore")
                    .header("Authorization", "Bearer " + admin)
            )
        );

        assertNotEquals(first.get("id"), restored.get("id"));
        assertNotEquals(second.get("id"), restored.get("id"));
        assertEquals(3, ((Number) restored.get("version")).intValue());
        assertEquals(true, restored.get("is_active"));
        assertEquals(configType, restored.get("config_type"));
        assertEquals(first.get("provider"), restored.get("provider"));
        assertEquals(first.get("model_name"), restored.get("model_name"));
        assertInstanceOf(Map.class, restored.get("config_json"));
        assertEquals(first.get("config_json"), restored.get("config_json"));

        UUID restoredId = UUID.fromString(restored.get("id").toString());
        var persisted = db.one("SELECT * FROM ai_configurations WHERE id=?", restoredId);
        assertEquals(restored.get("config_json"), persisted.get("config_json"));
        var restoredConfiguration = (Map<?, ?>) persisted.get("config_json");
        assertEquals(true, restoredConfiguration.get("stream"));
        assertInstanceOf(Number.class, restoredConfiguration.get("temperature"));
        assertInstanceOf(Map.class, restoredConfiguration.get("limits"));
        assertInstanceOf(List.class, restoredConfiguration.get("labels"));
        assertTrue(restoredConfiguration.containsKey("optional"));
        assertNull(restoredConfiguration.get("optional"));

        for (var original : List.of(first, second)) {
            var historical =
                db.one("SELECT * FROM ai_configurations WHERE id=?", UUID.fromString(original.get("id").toString()));
            assertEquals(false, historical.get("is_active"));
            assertEquals(original.get("version"), historical.get("version"));
            assertEquals(original.get("provider"), historical.get("provider"));
            assertEquals(original.get("model_name"), historical.get("model_name"));
            assertEquals(original.get("config_json"), historical.get("config_json"));
        }

        var history = db.list("SELECT * FROM ai_configurations WHERE config_type=? ORDER BY version", configType);
        assertEquals(3, history.size());
        assertEquals(1L, history.stream().filter(row -> Boolean.TRUE.equals(row.get("is_active"))).count());
        assertEquals(restoredId, history.getLast().get("id"));
        assertEquals(unrelatedBefore, db.one("SELECT * FROM ai_configurations WHERE id=?", unrelatedId));
        assertRestoreAudit("RESTORE_AI_CONFIGURATION", "AI_CONFIGURATION", first.get("id").toString(), restoredId);
    }

    @Test
    void promptRestoreCreatesNewActiveVersionAndKeepsOtherFeaturesUnchanged() throws Exception {
        String featureCode = "RESTORE_TEST_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        var first = postJson(
            "/api/v1/admin/ai/prompts",
            admin,
            Map.of(
                "featureCode",
                featureCode,
                "name",
                "Original Vietnamese prompt",
                "systemPrompt",
                "Hướng dẫn nghề nghiệp.\nGiữ nguyên thông tin người dùng.",
                "userPromptTemplate",
                "Review {{profile}} and {{job}}.\nReturn structured JSON."
            )
        );
        var second = postJson(
            "/api/v1/admin/ai/prompts",
            admin,
            Map.of(
                "featureCode",
                featureCode,
                "name",
                "Revised prompt",
                "systemPrompt",
                "Use the revised instructions.",
                "userPromptTemplate",
                "Review the revised {{profile}}."
            )
        );
        var unrelated = postJson(
            "/api/v1/admin/ai/prompts",
            admin,
            Map.of(
                "featureCode",
                featureCode + "_OTHER",
                "name",
                "Other feature prompt",
                "systemPrompt",
                "Keep these other instructions active.",
                "userPromptTemplate",
                "Explain {{other_context}}."
            )
        );
        UUID unrelatedId = UUID.fromString(unrelated.get("id").toString());
        var unrelatedBefore = db.one("SELECT * FROM ai_prompt_templates WHERE id=?", unrelatedId);

        var restored = response(
            mvc.perform(
                post("/api/v1/admin/ai/prompts/" + first.get("id") + "/restore")
                    .header("Authorization", "Bearer " + admin)
            )
        );

        assertNotEquals(first.get("id"), restored.get("id"));
        assertNotEquals(second.get("id"), restored.get("id"));
        assertEquals(3, ((Number) restored.get("version")).intValue());
        assertEquals(true, restored.get("is_active"));
        assertEquals(featureCode, restored.get("feature_code"));
        assertEquals(first.get("name"), restored.get("name"));
        assertEquals(first.get("system_prompt"), restored.get("system_prompt"));
        assertEquals(first.get("user_prompt_template"), restored.get("user_prompt_template"));

        for (var original : List.of(first, second)) {
            var historical =
                db.one("SELECT * FROM ai_prompt_templates WHERE id=?", UUID.fromString(original.get("id").toString()));
            assertEquals(false, historical.get("is_active"));
            assertEquals(original.get("version"), historical.get("version"));
            assertEquals(original.get("name"), historical.get("name"));
            assertEquals(original.get("system_prompt"), historical.get("system_prompt"));
            assertEquals(original.get("user_prompt_template"), historical.get("user_prompt_template"));
        }

        UUID restoredId = UUID.fromString(restored.get("id").toString());
        var persisted = db.one("SELECT * FROM ai_prompt_templates WHERE id=?", restoredId);
        assertEquals(first.get("system_prompt"), persisted.get("system_prompt"));
        assertEquals(first.get("user_prompt_template"), persisted.get("user_prompt_template"));
        var history = db.list("SELECT * FROM ai_prompt_templates WHERE feature_code=? ORDER BY version", featureCode);
        assertEquals(3, history.size());
        assertEquals(1L, history.stream().filter(row -> Boolean.TRUE.equals(row.get("is_active"))).count());
        assertEquals(restoredId, history.getLast().get("id"));
        assertEquals(unrelatedBefore, db.one("SELECT * FROM ai_prompt_templates WHERE id=?", unrelatedId));
        assertRestoreAudit("RESTORE_AI_PROMPT", "AI_PROMPT", first.get("id").toString(), restoredId);
    }

    @Test
    void aiRestorePreservesLegacySqlNullFields() throws Exception {
        String legacyKey =
            "LEGACY_" + UUID.randomUUID().toString().replace("-", "").replaceAll("[0-9]", "X").toUpperCase(Locale.ROOT);
        UUID configurationId =
            db.insert("ai_configurations", Map.of("config_type", legacyKey, "version", 4, "is_active", false));
        UUID promptId =
            db.insert("ai_prompt_templates", Map.of("feature_code", legacyKey, "version", 7, "is_active", false));

        var configuration = response(
            mvc.perform(
                post("/api/v1/admin/ai/configurations/" + configurationId + "/restore")
                    .header("Authorization", "Bearer " + admin)
            )
        );
        var prompt = response(
            mvc.perform(
                post("/api/v1/admin/ai/prompts/" + promptId + "/restore").header("Authorization", "Bearer " + admin)
            )
        );

        assertEquals(5, ((Number) configuration.get("version")).intValue());
        assertEquals(true, configuration.get("is_active"));
        assertNull(configuration.get("provider"));
        assertNull(configuration.get("model_name"));
        assertNull(configuration.get("config_json"));
        UUID restoredConfigurationId = UUID.fromString(configuration.get("id").toString());
        assertEquals(
            true,
            db.scalar(
                "SELECT provider IS NULL AND model_name IS NULL AND config_json IS NULL "
                    + "FROM ai_configurations WHERE id=?",
                restoredConfigurationId
            )
        );

        assertEquals(8, ((Number) prompt.get("version")).intValue());
        assertEquals(true, prompt.get("is_active"));
        assertNull(prompt.get("name"));
        assertNull(prompt.get("system_prompt"));
        assertNull(prompt.get("user_prompt_template"));
        UUID restoredPromptId = UUID.fromString(prompt.get("id").toString());
        assertEquals(
            true,
            db.scalar(
                "SELECT name IS NULL AND system_prompt IS NULL AND user_prompt_template IS NULL "
                    + "FROM ai_prompt_templates WHERE id=?",
                restoredPromptId
            )
        );
        assertEquals(false, db.scalar("SELECT is_active FROM ai_configurations WHERE id=?", configurationId));
        assertEquals(false, db.scalar("SELECT is_active FROM ai_prompt_templates WHERE id=?", promptId));
        assertRestoreAudit(
            "RESTORE_AI_CONFIGURATION",
            "AI_CONFIGURATION",
            configurationId.toString(),
            restoredConfigurationId
        );
        assertRestoreAudit("RESTORE_AI_PROMPT", "AI_PROMPT", promptId.toString(), restoredPromptId);
    }

    @Test
    void aiRestoreEndpointsRequireAdministratorAndValidateVersionIds() throws Exception {
        for (String group : List.of("configurations", "prompts")) {
            String prefix = "/api/v1/admin/ai/" + group + "/";
            String missingVersionPath = prefix + UUID.randomUUID() + "/restore";

            mvc.perform(post(missingVersionPath)).andExpect(status().isUnauthorized());
            mvc.perform(post(missingVersionPath).header("Authorization", "Bearer " + candidate))
                .andExpect(status().isForbidden());
            mvc.perform(post(missingVersionPath).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
            mvc.perform(post(prefix + "not-a-uuid/restore").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void consultationAnalysesExposeCompletedReportsAndHideOtherCandidatesAndInternals() throws Exception {
        UUID resumeId = confirmedCv();
        UUID jobId = importedJob();
        UUID threadId = assignedConsultation();
        var reports = seedCandidateReports(candidateId, resumeId, jobId, Instant.now(), "visible");
        db.update(
            "UPDATE resumes SET raw_content=?,file_url=? WHERE id=?",
            "PRIVATE_RAW_CV",
            "PRIVATE_UPLOAD_URL",
            resumeId
        );
        db.update("UPDATE assessment_sessions SET is_current=false WHERE id=?", reports.get("assessments"));
        db.update("UPDATE gap_analyses SET is_latest=false WHERE id=?", reports.get("gap_analyses"));
        db.update("UPDATE roadmaps SET status='completed',is_outdated=true WHERE id=?", reports.get("roadmaps"));
        UUID otherCandidateId = (UUID) tokens.authenticate(other).get("id");
        UUID otherResume = seedAnalysisResume(otherCandidateId, "READY");
        var hiddenReports = seedCandidateReports(otherCandidateId, otherResume, jobId, Instant.now(), "hidden");
        UUID draftResume = seedAnalysisResume(candidateId, "DRAFT");
        db.insert(
            "resume_analyses",
            Map.of("resume_id", draftResume, "analysis_type", "QUALITY", "summary", "PRIVATE_DRAFT_QUALITY")
        );
        db.insert(
            "job_resume_analyses",
            Map.of("user_id", candidateId, "resume_id", draftResume, "market_job_id", jobId)
        );
        db.insert(
            "assessment_sessions",
            Map.of("user_id", candidateId, "market_job_id", jobId, "status", "in_progress")
        );
        db.insert(
            "gap_analyses",
            Map.of("user_id", candidateId, "input_source", "CV", "status", "FAILED", "error", "PRIVATE_GAP_ERROR")
        );
        db.insert("roadmaps", Map.of("user_id", candidateId, "title", "Not generated yet", "status", "generating"));

        var result = candidateAnalyses(threadId, consultant, Map.of());

        assertEquals(candidateId.toString(), result.get("candidate_id"));
        assertEquals(20, result.get("limit"));
        assertEquals(0, result.get("offset"));
        assertTrue(result.containsKey("resume_id"));
        assertTrue(result.containsKey("job_id"));
        assertNull(result.get("resume_id"));
        assertNull(result.get("job_id"));
        for (String group : analysisGroups()) {
            assertEquals(List.of(reports.get(group).toString()), analysisIds(result, group), group);
        }

        var quality = analysisRows(result, "resume_analyses").getFirst();
        assertEquals("Quality visible", quality.get("summary"));
        assertEquals(1, ((List<?>) quality.get("suggestions")).size());
        assertTrue(json(quality.get("suggestions")).contains("Add measurable outcomes visible"));
        var ats = analysisRows(result, "ats_analyses").getFirst();
        assertEquals(List.of("Docker"), ats.get("missing_skills_json"));
        assertEquals(List.of("Improve projects visible"), ats.get("improvement_suggestions_json"));
        var match = analysisRows(result, "matching_results").getFirst();
        assertEquals("Candidate fits visible", ((Map<?, ?>) match.get("match_reason_json")).get("reason"));
        assertEquals(0.8, ((Number) match.get("final_score")).doubleValue());
        assertEquals(Map.of("Java", 80), analysisRows(result, "assessments").getFirst().get("skill_scores"));
        assertEquals(false, analysisRows(result, "assessments").getFirst().get("is_current"));
        assertTrue(
            json(analysisRows(result, "gap_analyses").getFirst().get("skills")).contains("Learn Docker visible")
        );
        assertEquals(false, analysisRows(result, "gap_analyses").getFirst().get("is_latest"));
        var roadmap = analysisRows(result, "roadmaps").getFirst();
        assertEquals("completed", roadmap.get("status"));
        assertEquals(true, roadmap.get("is_outdated"));
        var nodes = (List<?>) roadmap.get("nodes");
        assertEquals(2, nodes.size());
        var dependentNode = (Map<?, ?>) nodes.get(1);
        assertEquals(List.of(reports.get("prerequisite_node").toString()), dependentNode.get("prerequisite_ids"));
        assertTrue(json(dependentNode.get("resources")).contains("Java documentation visible"));

        String serialized = json(result);
        for (UUID hiddenId : hiddenReports.values()) {
            assertFalse(serialized.contains(hiddenId.toString()));
        }
        for (String forbidden : List.of(
            "password_hash",
            "accessToken",
            "refreshToken",
            "token_hash",
            "embedding",
            "raw_content",
            "profile_snapshot",
            "correct_option",
            "question_text",
            "input_hash",
            "PRIVATE_"
        )) {
            assertFalse(serialized.contains(forbidden), forbidden);
        }
        verify(ai, never()).generate(anyString(), anyMap());
        verify(ai, never()).embed(anyString());
    }

    @Test
    void consultationAnalysesApplyResourceFiltersAndPaginateEachGroupDeterministically() throws Exception {
        UUID firstResume = confirmedCv();
        UUID secondResume = confirmedCv();
        UUID firstJob = importedJob();
        UUID secondJob = importedJob();
        UUID threadId = assignedConsultation();
        Instant startedAt = Instant.now().minusSeconds(300);
        var first = seedCandidateReports(candidateId, firstResume, firstJob, startedAt, "first");
        var second = seedCandidateReports(candidateId, secondResume, secondJob, startedAt.plusSeconds(60), "second");
        var third = seedCandidateReports(candidateId, firstResume, secondJob, startedAt.plusSeconds(120), "third");
        var fourth = seedCandidateReports(candidateId, secondResume, firstJob, startedAt.plusSeconds(120), "fourth");

        var filtered = candidateAnalyses(
            threadId,
            consultant,
            Map.of("resumeId", firstResume.toString(), "jobId", secondJob.toString())
        );

        assertEquals(firstResume.toString(), filtered.get("resume_id"));
        assertEquals(secondJob.toString(), filtered.get("job_id"));
        assertEquals(
            List.of(third.get("resume_analyses").toString(), first.get("resume_analyses").toString()),
            analysisIds(filtered, "resume_analyses")
        );
        for (String group : List.of("ats_analyses", "matching_results", "gap_analyses", "roadmaps")) {
            assertEquals(List.of(third.get(group).toString()), analysisIds(filtered, group), group);
        }
        for (String group : List.of("salary_estimations", "assessments")) {
            assertEquals(
                List.of(third.get(group).toString(), second.get(group).toString()),
                analysisIds(filtered, group),
                group
            );
        }

        var page = candidateAnalyses(threadId, admin, Map.of("limit", "1", "offset", "1"));
        assertEquals(1, page.get("limit"));
        assertEquals(1, page.get("offset"));
        for (String group : analysisGroups()) {
            String firstTiedId = third.get(group).toString();
            String secondTiedId = fourth.get(group).toString();
            String expected = firstTiedId.compareTo(secondTiedId) < 0 ? firstTiedId : secondTiedId;
            assertEquals(List.of(expected), analysisIds(page, group), group);
        }

        var exhausted = candidateAnalyses(threadId, candidate, Map.of("limit", "1", "offset", "4"));
        for (String group : analysisGroups()) {
            assertEquals(List.of(), analysisIds(exhausted, group), group);
        }
    }

    @Test
    void consultationAnalysesFollowAssignmentPermissionsAndKeepProfileContractUnchanged() throws Exception {
        UUID resumeId = confirmedCv();
        var reports = seedCandidateReports(candidateId, resumeId, importedJob(), Instant.now(), "permissions");
        UUID threadId = db.insert("consultation_threads", Map.of("candidate_user_id", candidateId));
        String path = "/api/v1/consultations/" + threadId + "/candidate-analyses";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", "Bearer " + consultant)).andExpect(status().isNotFound());
        candidateAnalyses(threadId, candidate, Map.of());
        candidateAnalyses(threadId, admin, Map.of());
        response(
            mvc.perform(
                put("/api/v1/consultations/" + threadId + "/consultant").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("consultantId", consultantId)))
            )
        );
        var profileBefore = response(
            mvc.perform(
                get("/api/v1/consultations/" + threadId + "/candidate-profile")
                    .header("Authorization", "Bearer " + consultant)
            )
        );
        assertEquals(Set.of("candidate", "career_profile", "resume"), profileBefore.keySet());
        candidateAnalyses(threadId, consultant, Map.of());
        var profileAfter = response(
            mvc.perform(
                get("/api/v1/consultations/" + threadId + "/candidate-profile")
                    .header("Authorization", "Bearer " + consultant)
            )
        );
        assertEquals(profileBefore, profileAfter);

        UUID replacementConsultantId = createUser("CAREER_CONSULTANT");
        String replacement = access(replacementConsultantId);
        response(
            mvc.perform(
                put("/api/v1/consultations/" + threadId + "/consultant").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("consultantId", replacementConsultantId)))
            )
        );
        mvc.perform(get(path).header("Authorization", "Bearer " + consultant)).andExpect(status().isNotFound());
        var assignedReports = candidateAnalyses(threadId, replacement, Map.of());
        for (String group : analysisGroups()) {
            assertEquals(List.of(reports.get(group).toString()), analysisIds(assignedReports, group), group);
        }
        candidateAnalyses(threadId, candidate, Map.of());
        candidateAnalyses(threadId, admin, Map.of());

        response(mvc.perform(delete("/api/v1/me").header("Authorization", "Bearer " + candidate)));
        assertEquals("DELETED", db.one("SELECT status FROM users WHERE id=?::uuid", candidateId).get("status"));
        assertEquals(
            reports.get("resume_analyses"),
            db.one("SELECT id FROM resume_analyses WHERE id=?::uuid", reports.get("resume_analyses")).get("id")
        );
        for (String viewer : List.of(replacement, admin)) {
            mvc.perform(get(path).header("Authorization", "Bearer " + viewer)).andExpect(status().isNotFound());
            mvc.perform(
                get("/api/v1/consultations/" + threadId + "/candidate-profile")
                    .header("Authorization", "Bearer " + viewer)
            ).andExpect(status().isNotFound());
        }
    }

    @Test
    void consultationAnalysesRejectInvalidPaginationAndUnownedOrUnreadyResumeFilters() throws Exception {
        UUID threadId = assignedConsultation();
        String path = "/api/v1/consultations/" + threadId + "/candidate-analyses";
        for (String badLimit : List.of("0", "101")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + consultant).param("limit", badLimit))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).header("Authorization", "Bearer " + consultant).param("offset", "-1"))
            .andExpect(status().isBadRequest());
        for (String uuidParameter : List.of("resumeId", "jobId")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + consultant).param(uuidParameter, "not-a-uuid"))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(
            get("/api/v1/consultations/not-a-uuid/candidate-analyses").header("Authorization", "Bearer " + consultant)
        ).andExpect(status().isBadRequest());
        mvc.perform(
            get("/api/v1/consultations/" + UUID.randomUUID() + "/candidate-analyses")
                .header("Authorization", "Bearer " + admin)
        ).andExpect(status().isNotFound());

        UUID otherCandidateId = (UUID) tokens.authenticate(other).get("id");
        UUID otherResumeId = seedAnalysisResume(otherCandidateId, "READY");
        UUID draftResumeId = seedAnalysisResume(candidateId, "DRAFT");
        for (UUID invalidResumeId : List.of(otherResumeId, draftResumeId, UUID.randomUUID())) {
            mvc.perform(
                get(path).header("Authorization", "Bearer " + consultant).param("resumeId", invalidResumeId.toString())
            ).andExpect(status().isNotFound());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void consultantEvaluationCreatesCandidateReportsAndCachesAtsWithoutExposingInternals() throws Exception {
        UUID threadId = assignedConsultation(), resumeId = confirmedCv(), jobId = importedJob();
        UUID draftId = seedAnalysisResume(candidateId, "DRAFT");
        UUID foreignId = seedAnalysisResume((UUID) tokens.authenticate(other).get("id"), "READY");
        stubConsultantEvaluationAi();
        String base = "/api/v1/consultations/" + threadId;

        var listResult = mvc.perform(get(base + "/candidate-resumes").header("Authorization", "Bearer " + consultant))
            .andExpect(status().isOk()).andReturn();
        var resumes =
            (List<Map<String, Object>>) mapper.readValue(listResult.getResponse().getContentAsString(), List.class);
        assertEquals(List.of(resumeId.toString()), resumes.stream().map(row -> row.get("id").toString()).toList());
        assertEquals("READY", resumes.getFirst().get("status"));
        assertEquals("Java CV", resumes.getFirst().get("title"));
        for (String internal : List.of("raw_content", "structured_data", "file_url", "content_hash")) {
            assertFalse(resumes.getFirst().containsKey(internal), internal);
        }
        assertFalse(listResult.getResponse().getContentAsString().contains(draftId.toString()));
        assertFalse(listResult.getResponse().getContentAsString().contains(foreignId.toString()));

        var quality = postJson(base + "/resumes/" + resumeId + "/analyze", consultant, Map.of());
        assertEquals(resumeId.toString(), quality.get("resume_id"));
        assertEquals("AI_QUALITY", quality.get("analysis_type"));
        assertEquals(84, ((Number) quality.get("overall_score")).intValue());
        assertEquals("Add measurable project results", quality.get("summary"));
        assertEquals(List.of("Java experience"), quality.get("strengths_json"));
        var suggestions = (List<Map<String, Object>>) quality.get("suggestions");
        assertEquals(1, suggestions.size());
        assertEquals("Quantify backend performance", suggestions.getFirst().get("suggested_content"));
        assertFalse(json(quality).contains("PRIVATE_MODEL_TRACE"));
        assertFalse(json(quality).contains("_usage"));
        assertFalse(suggestions.getFirst().containsKey("_internal"));

        var ats = postJson(base + "/jobs/" + jobId + "/ats", consultant, Map.of("resumeId", resumeId));
        assertEquals(false, ats.get("cached"));
        assertEquals(candidateId.toString(), ats.get("user_id"));
        assertEquals(resumeId.toString(), ats.get("resume_id"));
        assertEquals(jobId.toString(), ats.get("market_job_id"));
        assertEquals(79, ((Number) ats.get("ats_score")).intValue());
        assertEquals(List.of("Docker"), ats.get("missing_skills_json"));
        assertEquals(List.of("Show production deployment evidence"), ats.get("improvement_suggestions_json"));
        assertFalse(json(ats).contains("PRIVATE_MODEL_TRACE"));
        assertFalse(json(ats).contains("input_hash"));
        assertFalse(json(ats).contains("_usage"));
        var cached = postJson(base + "/jobs/" + jobId + "/ats", consultant, Map.of("resumeId", resumeId));
        assertEquals(true, cached.get("cached"));
        assertEquals(ats.get("id"), cached.get("id"));
        assertEquals(ats.get("improvement_suggestions_json"), cached.get("improvement_suggestions_json"));
        when(ai.enabled()).thenReturn(false);
        var cachedWithoutAi = postJson(base + "/jobs/" + jobId + "/ats", consultant, Map.of("resumeId", resumeId));
        assertEquals(true, cachedWithoutAi.get("cached"));
        assertEquals(ats.get("id"), cachedWithoutAi.get("id"));
        verify(ai, times(1)).generate(eq("CV_ANALYZE"), anyMap());
        verify(ai, times(1)).generate(eq("JOB_ATS"), anyMap());
        assertEquals(
            candidateId,
            db.one("SELECT user_id FROM job_resume_analyses WHERE id=?::uuid", ats.get("id")).get("user_id")
        );
        assertEquals(
            2,
            ((Number) db.scalar(
                "SELECT count(*) FROM audit_logs WHERE actor_user_id=? "
                    + "AND entity_id=? AND new_value->>'analysis_id' IN (?, ?)",
                consultantId,
                threadId,
                quality.get("id"),
                ats.get("id")
            )).intValue()
        );

        for (String viewer : List.of(candidate, consultant, admin)) {
            var reports = candidateAnalyses(threadId, viewer, Map.of("resumeId", resumeId.toString()));
            assertTrue(analysisIds(reports, "resume_analyses").contains(quality.get("id")));
            assertTrue(analysisIds(reports, "ats_analyses").contains(ats.get("id")));
        }
    }

    @Test
    void consultantAtsCacheTracksCanonicalResumeJobSkillsAndModelChanges() throws Exception {
        UUID threadId = assignedConsultation(), resumeId = confirmedCv(), jobId = importedJob();
        stubConsultantEvaluationAi();
        String path = "/api/v1/consultations/" + threadId + "/jobs/" + jobId + "/ats";
        var first = postJson(path, consultant, Map.of("resumeId", resumeId));
        db.update(
            "UPDATE resumes SET structured_data=? WHERE id=?",
            db.json(Map.of("summary", "Java SQL developer with Docker deployment experience")),
            resumeId
        );
        var changedResume = postJson(path, consultant, Map.of("resumeId", resumeId));
        assertEquals(false, changedResume.get("cached"));
        assertNotEquals(first.get("id"), changedResume.get("id"));

        db.update("UPDATE market_job_posts SET parsed_requirements=? WHERE id=?", "Requires Kubernetes", jobId);
        var changedJob = postJson(path, consultant, Map.of("resumeId", resumeId));
        assertEquals(false, changedJob.get("cached"));
        assertNotEquals(changedResume.get("id"), changedJob.get("id"));

        db.update("UPDATE market_job_skills SET is_required=NOT is_required WHERE market_job_id=?", jobId);
        var changedSkills = postJson(path, consultant, Map.of("resumeId", resumeId));
        assertEquals(false, changedSkills.get("cached"));
        assertNotEquals(changedJob.get("id"), changedSkills.get("id"));

        when(ai.model()).thenReturn("test-model-v2");
        var changedModel = postJson(path, consultant, Map.of("resumeId", resumeId));
        assertEquals(false, changedModel.get("cached"));
        assertNotEquals(changedSkills.get("id"), changedModel.get("id"));
        when(ai.provider()).thenReturn("another-provider");
        var changedProvider = postJson(path, consultant, Map.of("resumeId", resumeId));
        assertEquals(false, changedProvider.get("cached"));
        assertNotEquals(changedModel.get("id"), changedProvider.get("id"));
        assertEquals(true, postJson(path, consultant, Map.of("resumeId", resumeId)).get("cached"));
        verify(ai, times(6)).generate(eq("JOB_ATS"), anyMap());
    }

    @Test
    void consultantEvaluationRejectsWrongRolesUnassignedThreadsAndReassignedConsultantsBeforeAi() throws Exception {
        UUID resumeId = confirmedCv(), jobId = importedJob(), threadId = assignedConsultation();
        stubConsultantEvaluationAi();
        String base = "/api/v1/consultations/" + threadId;
        mvc.perform(get(base + "/candidate-resumes")).andExpect(status().isUnauthorized());
        for (String wrongRole : List.of(candidate, other, admin)) {
            assertConsultantEvaluationDenied(threadId, resumeId, jobId, wrongRole, 403, true);
        }
        String anotherConsultant = access(createUser("CAREER_CONSULTANT"));
        assertConsultantEvaluationDenied(threadId, resumeId, jobId, anotherConsultant, 404, true);
        UUID unassigned = db.insert("consultation_threads", Map.of("candidate_user_id", candidateId));
        assertConsultantEvaluationDenied(unassigned, resumeId, jobId, consultant, 404, true);
        assertConsultantEvaluationDenied(UUID.randomUUID(), resumeId, jobId, consultant, 404, true);

        UUID replacementId = createUser("CAREER_CONSULTANT");
        response(
            mvc.perform(
                put(base + "/consultant").header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("consultantId", replacementId)))
            )
        );
        assertConsultantEvaluationDenied(threadId, resumeId, jobId, consultant, 404, true);
        mvc.perform(get(base + "/candidate-resumes").header("Authorization", "Bearer " + access(replacementId)))
            .andExpect(status().isOk());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void consultantEvaluationRejectsUnownedUnreadyAndMissingResumesBeforeAi() throws Exception {
        UUID threadId = assignedConsultation(), jobId = importedJob();
        UUID foreignId = seedAnalysisResume((UUID) tokens.authenticate(other).get("id"), "READY");
        UUID draftId = seedAnalysisResume(candidateId, "DRAFT");
        UUID parsingId = seedAnalysisResume(candidateId, "PARSING");
        stubConsultantEvaluationAi();
        for (UUID invalid : List.of(foreignId, draftId, parsingId, UUID.randomUUID())) {
            assertConsultantEvaluationDenied(threadId, invalid, jobId, consultant, 404, false);
        }
        mvc.perform(
            post("/api/v1/consultations/" + threadId + "/jobs/" + jobId + "/ats")
                .header("Authorization", "Bearer " + consultant).contentType(MediaType.APPLICATION_JSON).content("{}")
        ).andExpect(status().isBadRequest());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void consultantEvaluationRejectsClosedThreadsDeletedCandidatesAndDisabledAi() throws Exception {
        UUID threadId = assignedConsultation(), resumeId = confirmedCv(), jobId = importedJob();
        assertConsultantEvaluationDenied(threadId, resumeId, jobId, consultant, 503, false);
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(
            0,
            ((Number) db.scalar("SELECT count(*) FROM resume_analyses WHERE resume_id=?", resumeId)).intValue()
        );
        assertEquals(
            0,
            ((Number) db.scalar("SELECT count(*) FROM job_resume_analyses WHERE resume_id=?", resumeId)).intValue()
        );

        stubConsultantEvaluationAi();
        db.update("UPDATE consultation_threads SET status='CLOSED',closed_at=now() WHERE id=?", threadId);
        assertConsultantEvaluationDenied(threadId, resumeId, jobId, consultant, 409, false);
        mvc.perform(
            get("/api/v1/consultations/" + threadId + "/candidate-resumes")
                .header("Authorization", "Bearer " + consultant)
        ).andExpect(status().isOk());
        db.update("UPDATE consultation_threads SET status='OPEN',closed_at=NULL WHERE id=?", threadId);
        db.update("UPDATE users SET status='DELETED' WHERE id=?", candidateId);
        assertConsultantEvaluationDenied(threadId, resumeId, jobId, consultant, 404, true);
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void consultantAtsRejectsInactiveExpiredAndUnavailableJobsBeforeAi() throws Exception {
        UUID threadId = assignedConsultation(), resumeId = confirmedCv(), jobId = importedJob();
        stubConsultantEvaluationAi();
        String base = "/api/v1/consultations/" + threadId + "/jobs/";
        for (String state : List.of("EXPIRED", "REMOVED", "INVALID")) {
            db.update("UPDATE market_job_posts SET status=? WHERE id=?", state, jobId);
            mvc.perform(
                post(base + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
            ).andExpect(status().isNotFound());
        }
        db.update("UPDATE market_job_posts SET status='ACTIVE',valid_through=now()-interval '1 day' WHERE id=?", jobId);
        mvc.perform(
            post(base + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isNotFound());
        db.update("UPDATE market_job_posts SET valid_through=NULL WHERE id=?", jobId);
        db.update("UPDATE market_job_sources SET status='REMOVED' WHERE market_job_id=?", jobId);
        mvc.perform(
            post(base + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isNotFound());
        db.update(
            "UPDATE market_job_sources SET status='ACTIVE',valid_through=now()-interval '1 day' "
                + "WHERE market_job_id=?",
            jobId
        );
        mvc.perform(
            post(base + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isNotFound());
        mvc.perform(
            post(base + UUID.randomUUID() + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isNotFound());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void consultantEvaluationRejectsMalformedAiReportsWithoutPartialPersistence() throws Exception {
        UUID threadId = assignedConsultation(), resumeId = confirmedCv(), jobId = importedJob();
        stubConsultantEvaluationAi();
        String base = "/api/v1/consultations/" + threadId;
        var invalidQuality = fields(
            "content_score",
            80,
            "writing_quality",
            com.example.intelligent_cv_matching_ai_agent.resume.ResumeWritingTestData.writing(),
            "ats_score",
            70,
            "summary",
            "Review",
            "strengths",
            List.of(),
            "issues",
            List.of(),
            "suggestions",
            List.of("Invalid suggestion")
        );
        when(ai.generate(eq("CV_ANALYZE"), anyMap())).thenReturn(invalidQuality);
        mvc.perform(post(base + "/resumes/" + resumeId + "/analyze").header("Authorization", "Bearer " + consultant))
            .andExpect(status().isBadGateway());
        invalidQuality.put("suggestions", List.of());
        invalidQuality.put("content_score", 101);
        mvc.perform(post(base + "/resumes/" + resumeId + "/analyze").header("Authorization", "Bearer " + consultant))
            .andExpect(status().isBadGateway());
        invalidQuality.put("content_score", 80);
        invalidQuality.put("strengths", List.of(Map.of("_internal", "PRIVATE_MODEL_TRACE")));
        mvc.perform(post(base + "/resumes/" + resumeId + "/analyze").header("Authorization", "Bearer " + consultant))
            .andExpect(status().isBadGateway());

        var invalidAts = fields(
            "ats_score",
            75,
            "strengths",
            List.of(),
            "missing_skills",
            List.of(),
            "improvement_suggestions",
            "Invalid array"
        );
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(invalidAts);
        mvc.perform(
            post(base + "/jobs/" + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isBadGateway());
        invalidAts.put("improvement_suggestions", List.of());
        invalidAts.put("ats_score", -1);
        mvc.perform(
            post(base + "/jobs/" + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isBadGateway());
        invalidAts.put("ats_score", 75);
        invalidAts.put("missing_skills", List.of(Map.of("_internal", "PRIVATE_MODEL_TRACE")));
        mvc.perform(
            post(base + "/jobs/" + jobId + "/ats").header("Authorization", "Bearer " + consultant)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().isBadGateway());
        assertEquals(
            0,
            ((Number) db.scalar("SELECT count(*) FROM resume_analyses WHERE resume_id=?", resumeId)).intValue()
        );
        assertEquals(
            0,
            ((Number) db.scalar("SELECT count(*) FROM job_resume_analyses WHERE resume_id=?", resumeId)).intValue()
        );
        assertEquals(
            0,
            ((Number) db.scalar(
                "SELECT count(*) FROM audit_logs WHERE entity_id=? AND action IN "
                    + "('CONSULTATION_CV_ANALYZE', 'CONSULTATION_JOB_ATS')",
                threadId
            )).intValue()
        );
    }

    private void stubConsultantEvaluationAi() {
        when(ai.enabled()).thenReturn(true);
        when(ai.generate(eq("CV_ANALYZE"), anyMap())).thenReturn(
            fields(
                "content_score",
                85,
                "writing_quality",
                com.example.intelligent_cv_matching_ai_agent.resume.ResumeWritingTestData.writing(),
                "ats_score",
                82,
                "summary",
                "Add measurable project results",
                "strengths",
                List.of("Java experience"),
                "issues",
                List.of("Missing project outcomes"),
                "suggestions",
                List.of(
                    fields(
                        "category",
                        "PROJECTS",
                        "severity",
                        "MEDIUM",
                        "suggested_content",
                        "Quantify backend performance",
                        "explanation",
                        "Provide evidence",
                        "_internal",
                        "PRIVATE_MODEL_TRACE"
                    )
                ),
                "_model",
                "test-model",
                "_usage",
                Map.of("input_tokens", 32, "output_tokens", 16),
                "_internal",
                "PRIVATE_MODEL_TRACE"
            )
        );
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(
            fields(
                "ats_score",
                79,
                "strengths",
                List.of("Java"),
                "missing_skills",
                List.of("Docker"),
                "improvement_suggestions",
                List.of("Show production deployment evidence"),
                "_model",
                "test-model",
                "_usage",
                Map.of("input_tokens", 32, "output_tokens", 16),
                "_internal",
                "PRIVATE_MODEL_TRACE"
            )
        );
    }

    private void assertConsultantEvaluationDenied(
        UUID threadId,
        UUID resumeId,
        UUID jobId,
        String token,
        int expectedStatus,
        boolean includeList)
        throws Exception {
        String base = "/api/v1/consultations/" + threadId;
        if (includeList) {
            mvc.perform(get(base + "/candidate-resumes").header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus));
        }
        mvc.perform(post(base + "/resumes/" + resumeId + "/analyze").header("Authorization", "Bearer " + token))
            .andExpect(status().is(expectedStatus));
        mvc.perform(
            post(base + "/jobs/" + jobId + "/ats").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("resumeId", resumeId)))
        ).andExpect(status().is(expectedStatus));
    }

    private UUID assignedConsultation() {
        return db.insert(
            "consultation_threads",
            Map.of("candidate_user_id", candidateId, "consultant_user_id", consultantId)
        );
    }

    private UUID seedAnalysisResume(
        UUID ownerId,
        String status) {
        return db.insert(
            "resumes",
            Map.of(
                "user_id",
                ownerId,
                "title",
                "Report fixture CV",
                "source",
                "MANUAL",
                "status",
                status,
                "raw_content",
                "PRIVATE_RAW_CV",
                "structured_data",
                db.json(Map.of("summary", "Candidate facts"))
            )
        );
    }

    private Map<String, Object> candidateAnalyses(
        UUID threadId,
        String token,
        Map<String, String> parameters)
        throws Exception {
        var request =
            get("/api/v1/consultations/" + threadId + "/candidate-analyses").header("Authorization", "Bearer " + token);
        parameters.forEach(
            (
                name,
                value) -> request.param(name, value)
        );
        return response(mvc.perform(request));
    }

    private static List<String> analysisGroups() {
        return List.of(
            "resume_analyses",
            "ats_analyses",
            "matching_results",
            "salary_estimations",
            "assessments",
            "gap_analyses",
            "roadmaps"
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> analysisRows(
        Map<String, Object> response,
        String group) {
        return (List<Map<String, Object>>) response.get(group);
    }

    private static List<String> analysisIds(
        Map<String, Object> response,
        String group) {
        return analysisRows(response, group).stream().map(row -> row.get("id").toString()).toList();
    }

    private Map<String, UUID> seedCandidateReports(
        UUID ownerId,
        UUID resumeId,
        UUID jobId,
        Instant createdAt,
        String marker) {
        UUID quality = db.insert(
            "resume_analyses",
            fields(
                "resume_id",
                resumeId,
                "analysis_type",
                "QUALITY",
                "overall_score",
                85,
                "summary",
                "Quality " + marker,
                "strengths_json",
                db.json(List.of("Java")),
                "issues_json",
                db.json(List.of("Missing project outcomes")),
                "created_at",
                createdAt
            )
        );
        db.insert(
            "resume_analysis_suggestions",
            Map.of("analysis_id", quality, "suggested_content", "Add measurable outcomes " + marker)
        );
        UUID ats = db.insert(
            "job_resume_analyses",
            fields(
                "user_id",
                ownerId,
                "resume_id",
                resumeId,
                "market_job_id",
                jobId,
                "ats_score",
                80,
                "strengths_json",
                db.json(List.of("Java")),
                "missing_skills_json",
                db.json(List.of("Docker")),
                "improvement_suggestions_json",
                db.json(Map.of("input_hash", "PRIVATE_ATS_CACHE_KEY", "items", List.of("Improve projects " + marker))),
                "created_at",
                createdAt
            )
        );
        UUID run = db.insert(
            "job_matching_runs",
            Map.of(
                "user_id",
                ownerId,
                "basis",
                "SELECTED_CV",
                "status",
                "COMPLETED",
                "profile_snapshot",
                db.json(Map.of("private_prompt", "PRIVATE_PROFILE_SNAPSHOT"))
            )
        );
        db.update("INSERT INTO matching_run_resumes(matching_run_id,resume_id) VALUES(?,?)", run, resumeId);
        UUID match = db.insert(
            "job_match_results",
            fields(
                "matching_run_id",
                run,
                "market_job_id",
                jobId,
                "final_score",
                0.8,
                "match_reason_json",
                db.json(Map.of("reason", "Candidate fits " + marker)),
                "strengths_json",
                db.json(List.of("Java")),
                "skill_gaps_json",
                db.json(List.of("Docker")),
                "created_at",
                createdAt
            )
        );
        UUID salary = db.insert(
            "salary_estimations",
            fields(
                "user_id",
                ownerId,
                "market_job_id",
                jobId,
                "estimated_min",
                10000000,
                "estimated_max",
                15000000,
                "currency",
                "VND",
                "reasoning",
                db.json(List.of("Market estimate " + marker)),
                "created_at",
                createdAt
            )
        );
        UUID assessment = db.insert(
            "assessment_sessions",
            Map.of(
                "user_id",
                ownerId,
                "market_job_id",
                jobId,
                "status",
                "completed",
                "skill_scores",
                db.json(Map.of("Java", 80)),
                "created_at",
                createdAt
            )
        );
        db.insert(
            "assessment_questions",
            fields(
                "session_id",
                assessment,
                "sequence_order",
                0,
                "part",
                1,
                "question_text",
                "PRIVATE_QUESTION_TEXT",
                "options",
                db.json(Map.of("A", "Java", "B", "SQL")),
                "correct_option",
                "A",
                "explanation",
                "PRIVATE_ANSWER_EXPLANATION"
            )
        );
        UUID gap = db.insert(
            "gap_analyses",
            fields(
                "user_id",
                ownerId,
                "market_job_id",
                jobId,
                "resume_id",
                resumeId,
                "assessment_session_id",
                assessment,
                "input_source",
                "CV",
                "status",
                "COMPLETED",
                "summary",
                db.json(Map.of("reason", "Learning gaps " + marker)),
                "error",
                "PRIVATE_GENERATION_ERROR",
                "created_at",
                createdAt
            )
        );
        db.insert(
            "gap_analysis_skills",
            Map.of(
                "gap_analysis_id",
                gap,
                "skill_name",
                "Docker",
                "gap_status",
                "MISSING",
                "reasoning",
                "Learn Docker " + marker
            )
        );
        UUID roadmap = db.insert(
            "roadmaps",
            fields(
                "user_id",
                ownerId,
                "market_job_id",
                jobId,
                "gap_analysis_id",
                gap,
                "title",
                "Learning roadmap " + marker,
                "status",
                "ready",
                "created_at",
                createdAt
            )
        );
        UUID prerequisite =
            db.insert("roadmap_nodes", Map.of("roadmap_id", roadmap, "skill_name", "Java", "sequence_order", 1));
        UUID dependent =
            db.insert("roadmap_nodes", Map.of("roadmap_id", roadmap, "skill_name", "Spring Boot", "sequence_order", 2));
        db.update(
            "INSERT INTO roadmap_node_prerequisites(node_id,prerequisite_node_id) VALUES(?,?)",
            dependent,
            prerequisite
        );
        UUID resource = db.insert(
            "learning_resources",
            Map.of(
                "title",
                "Java documentation " + marker,
                "resource_type",
                "DOCUMENTATION",
                "url",
                "https://example.invalid/docs/" + UUID.randomUUID()
            )
        );
        db.update("INSERT INTO roadmap_node_resources(roadmap_node_id,resource_id) VALUES(?,?)", dependent, resource);

        return Map.of(
            "resume_analyses",
            quality,
            "ats_analyses",
            ats,
            "matching_results",
            match,
            "salary_estimations",
            salary,
            "assessments",
            assessment,
            "gap_analyses",
            gap,
            "roadmaps",
            roadmap,
            "prerequisite_node",
            prerequisite,
            "dependent_node",
            dependent
        );
    }

    private void assertRestoreAudit(
        String action,
        String entityType,
        String sourceVersionId,
        UUID restoredVersionId) {
        var audit = db.one("SELECT * FROM audit_logs WHERE action=? AND entity_id=?", action, restoredVersionId);
        assertEquals(entityType, audit.get("entity_type"));
        assertEquals(tokens.authenticate(admin).get("id"), audit.get("actor_user_id"));
        assertEquals(sourceVersionId, ((Map<?, ?>) audit.get("old_value")).get("id"));
        assertEquals(restoredVersionId.toString(), ((Map<?, ?>) audit.get("new_value")).get("id"));
        assertEquals(true, ((Map<?, ?>) audit.get("new_value")).get("is_active"));

        var adminAction =
            db.one("SELECT * FROM admin_actions WHERE action_type=? AND target_id=?", action, restoredVersionId);
        assertEquals(entityType, adminAction.get("target_type"));
        assertEquals(audit.get("actor_user_id"), adminAction.get("admin_user_id"));
    }
}
