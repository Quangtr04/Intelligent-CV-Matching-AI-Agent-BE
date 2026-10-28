package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
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
class CareerProfileSalaryIntegrationTests {

    private static final String BASE = "/api/v1/me/career-profile";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    private UUID owner;

    private String authorization;

    @BeforeEach
    void identity() {
        owner = db.insert(
            "users",
            Map.of(
                "email",
                "salary-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Salary Preference Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "CANDIDATE"
            )
        );
        authorization = "Bearer " + tokens.issue(owner).get("accessToken");
    }

    @Test
    void oneMonthlyPreferenceReplacesTheApiRangeAndPreservesHistoricalColumns() throws Exception {
        db.update(
            "INSERT INTO candidate_profiles(user_id,expected_salary_min,expected_salary_max) VALUES(?,10000000,20000000)",
            owner
        );
        mvc.perform(
            put(BASE).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(
                "{\"targetJobTitle\":\"Java Developer\",\"expectedSalary\":7500000,\"salaryCurrency\":\"VND\"}"
            )
        ).andExpect(status().isOk()).andExpect(jsonPath("$.expected_salary").value(7500000))
            .andExpect(jsonPath("$.salary_currency").value("VND"))
            .andExpect(jsonPath("$.target_job_title").value("Java Developer"))
            .andExpect(jsonPath("$.expected_salary_min").doesNotExist())
            .andExpect(jsonPath("$.expected_salary_max").doesNotExist());
        mvc.perform(get(BASE).header("Authorization", authorization)).andExpect(status().isOk())
            .andExpect(jsonPath("$.expected_salary").value(7500000))
            .andExpect(jsonPath("$.expected_salary_min").doesNotExist())
            .andExpect(jsonPath("$.expected_salary_max").doesNotExist());
        var stored = db.one("SELECT * FROM candidate_profiles WHERE user_id=?", owner);
        assertEquals(0, new BigDecimal("10000000").compareTo((BigDecimal) stored.get("expected_salary_min")));
        assertEquals(0, new BigDecimal("20000000").compareTo((BigDecimal) stored.get("expected_salary_max")));
        assertEquals(0, new BigDecimal("7500000").compareTo((BigDecimal) stored.get("expected_salary")));
    }

    @Test
    void nullExplicitlyClearsSalaryPreferenceAndMissingCurrencyDefaultsToVnd() throws Exception {
        db.update("INSERT INTO candidate_profiles(user_id,expected_salary) VALUES(?,7500000)", owner);
        mvc.perform(
            put(BASE).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSalary\":null}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.expected_salary").isEmpty())
            .andExpect(jsonPath("$.salary_currency").value("VND"));
        assertNull(db.scalar("SELECT expected_salary FROM candidate_profiles WHERE user_id=?", owner));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "7500000.001", "1000000000000", "\"7tr5\"", "true", "[]"})
    void invalidAmountsAreRejectedBeforeWritingTheProfile(
        String salary)
        throws Exception {
        mvc.perform(
            put(BASE).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSalary\":" + salary + "}")
        ).andExpect(status().isBadRequest());
        assertEquals(
            0L,
            ((Number) db.scalar("SELECT count(*) FROM candidate_profiles WHERE user_id=?", owner)).longValue()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"expectedSalaryMin", "expectedSalaryMax", "expected_salary_min", "expected_salary_max"})
    void retiredRangeFieldsCannotBeSilentlyIgnored(
        String field)
        throws Exception {
        mvc.perform(
            put(BASE).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedSalary\":7500000,\"" + field + "\":10000000}")
        ).andExpect(status().isBadRequest());
        assertEquals(
            0L,
            ((Number) db.scalar("SELECT count(*) FROM candidate_profiles WHERE user_id=?", owner)).longValue()
        );
    }
}
