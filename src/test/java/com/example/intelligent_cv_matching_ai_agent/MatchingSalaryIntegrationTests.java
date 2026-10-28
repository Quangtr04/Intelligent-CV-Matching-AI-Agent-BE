package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.SalaryExchangeRateService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SalaryExchangeRates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
class MatchingSalaryIntegrationTests {

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

    @MockitoBean
    private SalaryExchangeRateService exchangeRates;

    private UUID owner;

    private String authorization;

    @BeforeEach
    void fixture() {
        reset(ai, exchangeRates);
        when(exchangeRates.snapshot()).thenReturn(
            new SalaryExchangeRates(
                Map.of("USD", BigDecimal.ONE, "VND", new BigDecimal("25000"), "EUR", new BigDecimal("0.9")),
                Instant.parse("2026-10-24T00:00:00Z"),
                "https://rates.example.test"
            )
        );
        when(ai.provider()).thenReturn("ollama");
        when(ai.model()).thenReturn("qwen2.5:7b");
        when(ai.embeddingModel()).thenReturn("qwen3-embedding:0.6b");
        db.update("UPDATE market_job_posts SET status='REMOVED' WHERE status='ACTIVE'");
        owner = db.insert(
            "users",
            Map.of(
                "email",
                "matching-salary-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Matching Salary Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "CANDIDATE"
            )
        );
        authorization = "Bearer " + tokens.issue(owner).get("accessToken");
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title,profile_summary,expected_salary,salary_currency) "
                + "VALUES(?,'Java Developer','Java and SQL',7500000,'VND')",
            owner
        );
    }

    @Test
    void ruleBasedMatchingAcceptsTheInclusiveFloorAndHigherPayWithoutPromotingAnUnmatchedSkill() throws Exception {
        UUID floor = job("6.75 million VND/month", "java");
        UUID range = job("5 - 7 million VND/month", "java");
        UUID high = job("20 million VND/month", "python");
        job("6749999 VND/month", "java");
        UUID unknown = job("Negotiable", "java");
        UUID foreign = job("1000 USD/month", "java");
        UUID annual = job("120 million VND/year", "java");

        var run = match("CAREER_PROFILE", List.of());
        assertEquals("COMPLETED", run.get("status"));
        assertEquals(
            Set.of(
                floor.toString(),
                range.toString(),
                high.toString(),
                unknown.toString(),
                foreign.toString(),
                annual.toString()
            ),
            resultIds(run)
        );
        List<Map<String, Object>> results = results(run);
        assertEquals(high.toString(), results.getLast().get("market_job_id"));
        assertEquals(1.0, ((Number) results.getFirst().get("final_score")).doubleValue());
        assertEquals(0.0, ((Number) results.getLast().get("final_score")).doubleValue());
        Map<?, ?> policy = (Map<?, ?>) run.get("salary_filter");
        assertEquals(6, ((Number) policy.get("eligible_jobs")).intValue());
        assertEquals(4, ((Number) policy.get("verified_eligible_jobs")).intValue());
        assertEquals(2, ((Number) policy.get("unverified_eligible_jobs")).intValue());
        assertEquals(6750000.0, ((Number) policy.get("minimum_acceptable")).doubleValue());
        assertEquals("AT_LEAST_CONFIGURED_MINIMUM", policy.get("comparison"));
        for (Map<String, Object> result : results) {
            Map<?, ?> reason = (Map<?, ?>) result.get("match_reason_json");
            assertEquals("VND", ((Map<?, ?>) reason.get("salary_match")).get("currency"));
        }
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void semanticRetrievalFiltersSalaryBeforeTheTopTwentyVectorCandidates() throws Exception {
        when(ai.enabled()).thenReturn(true);
        db.update("SET LOCAL hnsw.ef_search=1");
        db.update("SET LOCAL hnsw.iterative_scan=off");
        db.update("SET LOCAL enable_seqscan=off");
        float[] query = queryVector();
        when(ai.embed(anyString())).thenReturn(query);
        for (int index = 0; index < 25; index++) {
            embedding(job("1 million VND/month", "java"), 1.0, 0.0);
        }
        UUID eligible = job("20 million VND/month", "java");
        embedding(eligible, 0.8, 0.6);
        when(ai.generate(eq("JOB_MATCH_RERANK"), anyMap())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(1);
            List<?> jobs = (List<?>) input.get("jobs");
            assertEquals(1, jobs.size());
            assertEquals(eligible, ((Map<?, ?>) jobs.getFirst()).get("id"));
            return Map
                .of("results", List.of(Map.of("job_id", eligible.toString(), "score", 0.9, "reason", "Java match")));
        });

        var run = match("CAREER_PROFILE", List.of());
        assertEquals(Set.of(eligible.toString()), resultIds(run));
        assertEquals(0.8, ((Number) results(run).getFirst().get("vector_similarity_score")).doubleValue(), 0.00001);
        verify(ai).embed(anyString());
        verify(ai).generate(eq("JOB_MATCH_RERANK"), anyMap());
        assertEquals(26, ((Number) ((Map<?, ?>) run.get("salary_filter")).get("examined_jobs")).intValue());
    }

    @Test
    void knownSalariesBelowTheFloorCompleteWithoutEmbeddingOrGeneratingEvenWhenAiIsEnabled() throws Exception {
        when(ai.enabled()).thenReturn(true);
        job("6 million VND/month", "java");
        var run = match("CAREER_PROFILE", List.of());
        assertEquals("COMPLETED", run.get("status"));
        assertTrue(results(run).isEmpty());
        assertEquals(0, ((Number) ((Map<?, ?>) run.get("salary_filter")).get("eligible_jobs")).intValue());
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void missingEligibleEmbeddingsUseSkillsInsteadOfFailingRecommendations() throws Exception {
        when(ai.enabled()).thenReturn(true);
        when(ai.embed(anyString())).thenReturn(queryVector());
        embedding(job("1 million VND/month", "java"), 1.0, 0.0);
        UUID eligible = job("20 million VND/month", "java");
        var run = match("CAREER_PROFILE", List.of());
        assertEquals(Set.of(eligible.toString()), resultIds(run));
        assertEquals("RULE_BASED", run.get("provider"));
        assertEquals("SKILL_OVERLAP_UNINDEXED_JOBS", ((Map<?, ?>) run.get("profile_snapshot")).get("mode"));
        assertNull(results(run).getFirst().get("llm_rerank_score"));
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAREER_PROFILE", "SELECTED_CV", "ALL_CVS"})
    void undisclosedAndConvertedSalaryJobsRemainAvailableWithoutAnEmbeddingIndex(
        String basis)
        throws Exception {
        UUID cv = db.insert(
            "resumes",
            Map.of(
                "user_id",
                owner,
                "title",
                "Java CV",
                "source",
                "MANUAL",
                "status",
                "READY",
                "raw_content",
                "Java and SQL experience"
            )
        );
        UUID unknown = job("You'll love it USD / MONTH", "java");
        UUID foreign = job("800 - 2,000 USD USD / MONTH", "java");
        UUID ambiguous = job("Up to 35tr (Thương lượng) USD / MONTH", "java");
        job("200 USD/month", "java");
        when(ai.enabled()).thenReturn(true);
        when(ai.embed(anyString())).thenReturn(queryVector());

        var run = match(basis, "SELECTED_CV".equals(basis) ? List.of(cv) : List.of());
        assertEquals(Set.of(unknown.toString(), foreign.toString(), ambiguous.toString()), resultIds(run));
        assertEquals("SKILL_OVERLAP_UNINDEXED_JOBS", ((Map<?, ?>) run.get("profile_snapshot")).get("mode"));
        for (var result : results(run)) {
            Map<?, ?> salary = (Map<?, ?>) ((Map<?, ?>) result.get("match_reason_json")).get("salary_match");
            boolean converted = foreign.toString().equals(result.get("market_job_id"));
            assertEquals(converted ? "VERIFIED" : "UNVERIFIED", salary.get("status"));
            assertEquals(converted, salary.get("verified"));
            if (converted) {
                assertEquals("USD", salary.get("job_currency"));
                assertEquals(20000000d, ((Number) salary.get("comparison_salary_min")).doubleValue());
            } else {
                assertFalse(salary.containsKey("difference_percent"));
                assertFalse(salary.containsKey("closest_offer"));
            }
        }
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void changingSalaryExpectationAndJobSalaryInvalidatesDatabaseMatchingCache() throws Exception {
        UUID job = job("7 million VND/month", "java");
        var first = match("CAREER_PROFILE", List.of());
        assertEquals(Set.of(job.toString()), resultIds(first));
        var cached = match("CAREER_PROFILE", List.of());
        assertEquals(first.get("id"), cached.get("id"));
        assertEquals(true, cached.get("cached"));

        db.update("UPDATE candidate_profiles SET expected_salary=10000000 WHERE user_id=?", owner);
        var changedPreference = match("CAREER_PROFILE", List.of());
        assertNotEquals(first.get("id"), changedPreference.get("id"));
        assertTrue(results(changedPreference).isEmpty());

        db.update("UPDATE market_job_posts SET salary_text='20 million VND/month' WHERE id=?", job);
        var changedJobSalary = match("CAREER_PROFILE", List.of());
        assertNotEquals(changedPreference.get("id"), changedJobSalary.get("id"));
        assertEquals(Set.of(job.toString()), resultIds(changedJobSalary));
        assertEquals(false, changedJobSalary.get("cached"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorPolicyChangeInvalidatesMatchingCacheAndPreservesHistoricalPolicy(
        boolean aiEnabled)
        throws Exception {
        when(ai.enabled()).thenReturn(aiEnabled);
        UUID low = job("6 million VND/month", "java");
        UUID high = job("7.5 million VND/month", "java");
        if (aiEnabled) {
            embedding(low, 0.8, 0.6);
            embedding(high, 1, 0);
            when(ai.embed(anyString())).thenReturn(queryVector());
            when(ai.generate(eq("JOB_MATCH_RERANK"), anyMap())).thenAnswer(call -> {
                Map<?, ?> input = call.getArgument(1);
                List<?> jobs = (List<?>) input.get("jobs");
                return Map.of(
                    "results",
                    jobs.stream()
                        .map(
                            value -> Map.of(
                                "job_id",
                                ((Map<?, ?>) value).get("id").toString(),
                                "score",
                                0.9,
                                "reason",
                                "Java match"
                            )
                        ).toList()
                );
            });
        }
        var original = match("CAREER_PROFILE", List.of());
        assertEquals(Set.of(high.toString()), resultIds(original));
        assertEquals(original.get("id"), match("CAREER_PROFILE", List.of()).get("id"));
        UUID administrator = db.insert(
            "users",
            Map.of(
                "email",
                "salary-admin-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Salary Settings Administrator",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "ADMIN"
            )
        );
        String admin = "Bearer " + tokens.issue(administrator).get("accessToken");
        mvc.perform(
            put("/api/v1/admin/matching/salary-policy").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tolerancePercent\":20}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        var relaxed = match("CAREER_PROFILE", List.of());
        assertNotEquals(original.get("id"), relaxed.get("id"));
        assertEquals(false, relaxed.get("cached"));
        assertEquals(Set.of(low.toString(), high.toString()), resultIds(relaxed));
        assertEquals(
            20.0,
            ((Number) ((Map<?, ?>) relaxed.get("salary_filter")).get("tolerance_percent")).doubleValue()
        );
        assertEquals(
            6000000.0,
            ((Number) ((Map<?, ?>) relaxed.get("salary_filter")).get("minimum_acceptable")).doubleValue()
        );
        assertEquals(relaxed.get("id"), match("CAREER_PROFILE", List.of()).get("id"));
        mvc.perform(get("/api/v1/matching/" + original.get("id")).header("Authorization", authorization))
            .andExpect(status().isOk()).andExpect(jsonPath("$.salary_filter.tolerance_percent").value(10))
            .andExpect(jsonPath("$.salary_filter.settings_version").value(1))
            .andExpect(jsonPath("$.salary_filter.minimum_acceptable").value(6750000));
        mvc.perform(
            put("/api/v1/admin/matching/salary-policy").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tolerancePercent\":0}")
        ).andExpect(status().isOk());
        var strict = match("CAREER_PROFILE", List.of());
        assertEquals(Set.of(high.toString()), resultIds(strict));
        assertNotEquals(original.get("id"), strict.get("id"));
    }

    @Test
    void profileMatchingRequiresAnExpectedSalaryBeforeAnyAiWork() throws Exception {
        db.update("UPDATE candidate_profiles SET expected_salary=NULL WHERE user_id=?", owner);
        job("20 million VND/month", "java");
        mvc.perform(
            post("/api/v1/matching").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"basis\":\"CAREER_PROFILE\",\"limit\":10}")
        ).andExpect(status().isBadRequest());
        verify(ai, never()).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void selectedCvUsesTheProfileSalaryAndRecomputesAfterItChangesWhileNoPreferenceStaysOptional() throws Exception {
        UUID resume = db.insert(
            "resumes",
            Map.of(
                "user_id",
                owner,
                "title",
                "Matching Salary CV",
                "source",
                "MANUAL",
                "status",
                "READY",
                "raw_content",
                "Java and SQL experience"
            )
        );
        UUID low = job("6 million VND/month", "java");
        UUID high = job("20 million VND/month", "java");
        var first = match("SELECTED_CV", List.of(resume));
        assertEquals(Set.of(high.toString()), resultIds(first));
        db.update("UPDATE candidate_profiles SET expected_salary=5000000 WHERE user_id=?", owner);
        var changed = match("SELECTED_CV", List.of(resume));
        assertNotEquals(first.get("id"), changed.get("id"));
        assertEquals(Set.of(low.toString(), high.toString()), resultIds(changed));

        db.update("UPDATE candidate_profiles SET expected_salary=NULL WHERE user_id=?", owner);
        UUID unknown = job("Negotiable", "java");
        var optional = match("SELECTED_CV", List.of(resume));
        assertEquals(Set.of(low.toString(), high.toString(), unknown.toString()), resultIds(optional));
        assertEquals(false, ((Map<?, ?>) optional.get("salary_filter")).get("active"));
    }

    private UUID job(
        String salary,
        String skill) {
        UUID id = db.insert(
            "market_job_posts",
            Map.of(
                "source_site",
                "SALARY_TEST",
                "raw_content_hash",
                UUID.randomUUID().toString(),
                "job_title",
                "Test Developer",
                "role_category",
                "BACKEND",
                "raw_content",
                "Developer role requiring " + skill,
                "salary_text",
                salary
            )
        );
        db.insert(
            "market_job_skills",
            Map.of("market_job_id", id, "skill_name", skill, "normalized_skill", skill, "is_required", true)
        );
        return id;
    }

    private void embedding(
        UUID job,
        double first,
        double second) {
        double[] vector = new double[1024];
        vector[0] = first;
        vector[1] = second;
        db.update(
            "INSERT INTO market_job_embeddings(market_job_id,provider,model,embedding,content_hash,is_current) "
                + "SELECT id,'ollama','qwen3-embedding:0.6b',?::vector,raw_content_hash,true "
                + "FROM market_job_posts WHERE id=?",
            Arrays.toString(vector),
            job
        );
    }

    private static float[] queryVector() {
        float[] vector = new float[1024];
        vector[0] = 1;
        return vector;
    }

    private Map<String, Object> match(
        String basis,
        List<UUID> resumeIds)
        throws Exception {
        MvcResult response = mvc.perform(
            post("/api/v1/matching").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("basis", basis, "resumeIds", resumeIds, "limit", 10)))
        ).andExpect(status().isOk()).andReturn();
        return mapper.readValue(response.getResponse().getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(
        Map<String, Object> run) {
        return (List<Map<String, Object>>) run.get("results");
    }

    private static Set<String> resultIds(
        Map<String, Object> run) {
        return results(run).stream().map(result -> result.get("market_job_id").toString()).collect(Collectors.toSet());
    }
}
