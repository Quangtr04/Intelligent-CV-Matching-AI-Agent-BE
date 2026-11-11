package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP binding, bearer authorization and SQL; synthetic fixtures always roll back. */
@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.consultation.google.enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false",
        "logging.level.org.springframework.jdbc=OFF"}
)
@AutoConfigureMockMvc
@Transactional
class ConsultationBookingAttachmentHttpTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private UUID candidate;

    private UUID consultant;

    private UUID outsider;

    private UUID resume;

    private UUID unsharedResume;

    private UUID foreignResume;

    private UUID credit;

    private String ownerToken;

    private String consultantToken;

    private String outsiderToken;

    @DynamicPropertySource
    static void isolatedDatabase(
        DynamicPropertyRegistry registry) {
        String url = System.getProperty("consultation.testDatabaseUrl", "");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "edunexus_test");
        registry.add("spring.datasource.password", () -> "");
    }

    @BeforeEach
    void fixtures() {
        candidate = person("CANDIDATE");
        consultant = person("CAREER_CONSULTANT");
        outsider = person("CAREER_CONSULTANT");
        UUID otherCandidate = person("CANDIDATE");
        resume = resume(candidate, "Selected CV", false);
        unsharedResume = resume(candidate, "Private unshared CV", true);
        foreignResume = resume(otherCandidate, "Another candidate CV", false);
        db.update(
            "INSERT INTO consultant_profiles(user_id,target_roles,bio) VALUES (?,?,?)",
            consultant,
            db.json(List.of("Backend")),
            "Fixture advisor"
        );
        UUID product = (UUID) db.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_PACK_5_60'");
        credit = db.insert(
            "consultation_orders",
            fields(
                "candidate_user_id",
                candidate,
                "service_id",
                product,
                "service_code",
                "CAREER_PACK_5_60",
                "service_name",
                "Fixture session pack",
                "delivery_mode",
                "LIVE",
                "duration_minutes",
                60,
                "amount",
                895000,
                "currency",
                "VND",
                "subject",
                "Fixture pack",
                "description",
                "",
                "sessions_total",
                5,
                "sessions_remaining",
                5,
                "is_session_pack",
                true,
                "status",
                "PAID"
            )
        );
        ownerToken = bearer(candidate);
        consultantToken = bearer(consultant);
        outsiderToken = bearer(outsider);
    }

    @Test
    void missingOrUnownedResumeIsRejectedWithoutReservingCredit() throws Exception {
        Map<String, Object> missing = input();
        missing.remove("resumeId");
        mvc.perform(
            post("/api/v1/consultation-bookings").header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(missing))
        ).andExpect(status().isBadRequest());
        Map<String, Object> unowned = input();
        unowned.put("resumeId", foreignResume);
        mvc.perform(
            post("/api/v1/consultation-bookings").header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(unowned))
        ).andExpect(status().isNotFound());
        assertEquals(5, remaining());
    }

    @Test
    void cvOnlyBookingRoundTripsItsSelectionAndRestrictsPreparationToParticipants() throws Exception {
        Map<?, ?> booking = create();
        String path = "/api/v1/consultation-bookings/" + booking.get("id") + "/preparation";
        for (String token : List.of(ownerToken, consultantToken)) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resume.id").value(resume.toString()))
                .andExpect(jsonPath("$.resume.title").value("Selected CV"))
                .andExpect(jsonPath("$.resume.file_url").doesNotExist()).andExpect(jsonPath("$.job").doesNotExist())
                .andExpect(jsonPath("$.ats_analysis").doesNotExist());
        }
        mvc.perform(get(path).header("Authorization", outsiderToken)).andExpect(status().isNotFound());
        assertEquals(4, remaining());
    }

    @Test
    void olderConsultationEndpointsCannotFallBackToAnotherOwnedCv() throws Exception {
        Map<?, ?> booking = create();
        String thread = "/api/v1/consultations/" + booking.get("thread_id");
        mvc.perform(get(thread + "/candidate-profile").header("Authorization", consultantToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resume.id").value(resume.toString()));
        mvc.perform(
            get(thread + "/candidate-profile").param("resumeId", unsharedResume.toString())
                .header("Authorization", consultantToken)
        ).andExpect(status().isNotFound());
        mvc.perform(get(thread + "/candidate-resumes").header("Authorization", consultantToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(resume.toString()));
    }

    @Test
    void cancellingBookingRevokesConsultantDocumentAccessWhileOwnerKeepsOwnCv() throws Exception {
        Map<?, ?> booking = create();
        String base = "/api/v1/consultation-bookings/" + booking.get("id");
        String thread = "/api/v1/consultations/" + booking.get("thread_id");
        mvc.perform(post(base + "/cancel").header("Authorization", ownerToken)).andExpect(status().isOk());
        for (String path : List.of(
            base + "/preparation",
            thread + "/candidate-profile",
            thread + "/candidate-analyses",
            thread + "/candidate-resumes"
        )) {
            mvc.perform(get(path).header("Authorization", consultantToken)).andExpect(status().isNotFound());
        }
        mvc.perform(get(base + "/preparation").header("Authorization", ownerToken)).andExpect(status().isOk())
            .andExpect(jsonPath("$.resume.id").value(resume.toString()));
        assertEquals(5, remaining());
    }

    @Test
    void deletingCandidateAccountRevokesSharedDocumentAccess() throws Exception {
        Map<?, ?> booking = create();
        String preparation = "/api/v1/consultation-bookings/" + booking.get("id") + "/preparation";
        String thread = "/api/v1/consultations/" + booking.get("thread_id");
        mvc.perform(get(preparation).header("Authorization", consultantToken)).andExpect(status().isOk());
        db.update("UPDATE users SET status='DELETED' WHERE id=?", candidate);
        for (String path : List.of(
            preparation,
            thread + "/candidate-profile",
            thread + "/candidate-analyses",
            thread + "/candidate-resumes"
        )) {
            mvc.perform(get(path).header("Authorization", consultantToken)).andExpect(status().isNotFound());
        }
    }

    private Map<?, ?> create() throws Exception {
        String body = mvc
            .perform(
                post("/api/v1/consultation-bookings").header("Authorization", ownerToken)
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input()))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.resume_id").value(resume.toString()))
            .andExpect(jsonPath("$.job_id").doesNotExist()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(body, Map.class);
    }

    private Map<String, Object> input() {
        return new LinkedHashMap<>(
            fields(
                "consultantId",
                consultant,
                "creditOrderId",
                credit,
                "resumeId",
                resume,
                "targetRole",
                "Backend",
                "subject",
                "Review selected CV",
                "description",
                "Prepare for interview",
                "startsAt",
                OffsetDateTime.now().plusDays(2).toString()
            )
        );
    }

    private int remaining() {
        return ((Number) db.scalar("SELECT sessions_remaining FROM consultation_orders WHERE id=?", credit)).intValue();
    }

    private String bearer(
        UUID person) {
        return "Bearer " + tokens.issue(person).get("accessToken");
    }

    private UUID person(
        String role) {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@example.test",
                "auth_provider",
                "GOOGLE",
                "google_sub",
                UUID.randomUUID().toString(),
                "full_name",
                "Fixture " + role,
                "role",
                role,
                "status",
                "ACTIVE"
            )
        );
    }

    private UUID resume(
        UUID owner,
        String title,
        boolean primary) {
        return db.insert(
            "resumes",
            fields(
                "user_id",
                owner,
                "title",
                title,
                "source",
                "MANUAL",
                "status",
                "READY",
                "is_primary",
                primary,
                "structured_data",
                db.json(Map.of("summary", "Reviewed experience", "skills", List.of(Map.of("name", "Java"))))
            )
        );
    }
}
