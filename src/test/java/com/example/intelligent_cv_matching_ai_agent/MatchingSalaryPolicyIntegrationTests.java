package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.MatchingSalarySettingsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.MatchingSalaryPolicyService;
import java.math.BigDecimal;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.*;
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
class MatchingSalaryPolicyIntegrationTests {

    private static final String ADMIN = "/api/v1/admin/matching/salary-policy";

    private static final String CANDIDATE = "/api/v1/matching/salary-policy";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    private UUID administrator;

    private String adminToken;

    private String candidateToken;

    @BeforeEach
    void identities() {
        administrator = user("ADMIN");
        adminToken = token(administrator);
        candidateToken = token(user("CANDIDATE"));
    }

    @Test
    void defaultSingletonPolicyIsVisibleToAuthorizedAdministratorsAndCandidates() throws Exception {
        mvc.perform(get(ADMIN).header("Authorization", adminToken)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tolerancePercent").value(10)).andExpect(jsonPath("$.minimumRatio").value(0.9))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.updatedAt").isNotEmpty());
        mvc.perform(get(CANDIDATE).header("Authorization", candidateToken)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tolerancePercent").value(10)).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.updatedBy").doesNotExist());
        assertEquals(1L, db.scalar("SELECT count(*) FROM matching_salary_settings"));
    }

    @Test
    void updatesPersistActorVersionAndAuditAndEquivalentValuesAreNoOps() throws Exception {
        mvc.perform(
            put(ADMIN).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercent\":20}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.tolerancePercent").value(20))
            .andExpect(jsonPath("$.minimumRatio").value(0.8)).andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.updatedBy").value(administrator.toString()));
        Map<String, Object> saved = db.one("SELECT * FROM matching_salary_settings WHERE id=1");
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) saved.get("tolerance_percent")));
        assertEquals(administrator, saved.get("updated_by"));
        var recreated = new MatchingSalaryPolicyService(
            new MatchingSalarySettingsRepository(db),
            new CurrentUser(),
            mock(AuditService.class)
        );
        assertEquals(new BigDecimal("20.00"), recreated.effective().tolerancePercent());
        assertEquals(2, recreated.effective().version());
        mvc.perform(get(ADMIN).header("Authorization", adminToken)).andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.tolerancePercent").value(20));
        mvc.perform(
            put(ADMIN).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercent\":20.00}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertEquals(saved, db.one("SELECT * FROM matching_salary_settings WHERE id=1"));
        assertEquals(
            1L,
            db.scalar(
                "SELECT count(*) FROM audit_logs WHERE action='UPDATE_MATCHING_SALARY_POLICY'" + " AND actor_user_id=?",
                administrator
            )
        );
        Map<String, Object> audit = db.one(
            "SELECT old_value,new_value FROM audit_logs"
                + " WHERE action='UPDATE_MATCHING_SALARY_POLICY' AND actor_user_id=?",
            administrator
        );
        assertEquals(10.0, ((Number) ((Map<?, ?>) audit.get("old_value")).get("tolerance_percent")).doubleValue());
        assertEquals(20.0, ((Number) ((Map<?, ?>) audit.get("new_value")).get("tolerance_percent")).doubleValue());
        mvc.perform(get(CANDIDATE).header("Authorization", candidateToken)).andExpect(status().isOk())
            .andExpect(jsonPath("$.tolerancePercent").value(20)).andExpect(jsonPath("$.updatedBy").doesNotExist());
    }

    @Test
    void candidateCannotChangePolicyAndEveryRouteEnforcesAuthenticationAndRole() throws Exception {
        mvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        mvc.perform(get(CANDIDATE)).andExpect(status().isUnauthorized());
        mvc.perform(put(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"tolerancePercent\":20}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN).header("Authorization", candidateToken)).andExpect(status().isForbidden());
        mvc.perform(
            put(ADMIN).header("Authorization", candidateToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercent\":20}")
        ).andExpect(status().isForbidden());
        mvc.perform(get(CANDIDATE).header("Authorization", adminToken)).andExpect(status().isForbidden());
        mvc.perform(
            put(CANDIDATE).header("Authorization", candidateToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercent\":20}")
        ).andExpect(status().isMethodNotAllowed());
        assertEquals(new BigDecimal("10.00"), db.scalar("SELECT tolerance_percent FROM matching_salary_settings"));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidInputCannotChangePersistedPolicyOrAudit(
        String body)
        throws Exception {
        Map<String, Object> before = db.one("SELECT * FROM matching_salary_settings WHERE id=1");
        mvc.perform(
            put(ADMIN).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isBadRequest());
        assertEquals(before, db.one("SELECT * FROM matching_salary_settings WHERE id=1"));
        assertEquals(
            0L,
            db.scalar(
                "SELECT count(*) FROM audit_logs WHERE action='UPDATE_MATCHING_SALARY_POLICY'" + " AND actor_user_id=?",
                administrator
            )
        );
    }

    private static Stream<String> invalidInputs() {
        return Stream.of(
            "{}",
            "{\"tolerancePercent\":null}",
            "{\"tolerancePercent\":-0.01}",
            "{\"tolerancePercent\":100.01}",
            "{\"tolerancePercent\":1.234}",
            "{\"tolerancePercent\":20.000}",
            "{\"tolerancePercent\":\"20\"}",
            "{\"tolerancePercent\":true}",
            "{\"tolerancePercent\":[]}"
        );
    }

    @ParameterizedTest
    @MethodSource("acceptedInputs")
    void acceptsZeroFractionalAndFullToleranceBoundaries(
        String percentage)
        throws Exception {
        mvc.perform(
            put(ADMIN).header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tolerancePercent\":" + percentage + "}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.tolerancePercent").value(Double.parseDouble(percentage)));
    }

    private static Stream<String> acceptedInputs() {
        return Stream.of("0", "12.34", "100");
    }

    @ParameterizedTest
    @MethodSource("invalidDatabaseUpdates")
    void databaseRejectsInvalidSingletonValuesOutsideTheApi(
        String assignment) {
        assertThrows(
            DataAccessException.class,
            () -> db.update("UPDATE matching_salary_settings SET " + assignment + " WHERE id=1")
        );
    }

    private static Stream<String> invalidDatabaseUpdates() {
        return Stream.of(
            "tolerance_percent=-1",
            "tolerance_percent=101",
            "tolerance_percent=10.001",
            "tolerance_percent=NULL",
            "version=0",
            "id=2",
            "updated_by='ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid"
        );
    }

    private UUID user(
        String role) {
        return db.insert(
            "users",
            Map.of(
                "email",
                "salary-policy-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Salary Policy Test",
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
}
