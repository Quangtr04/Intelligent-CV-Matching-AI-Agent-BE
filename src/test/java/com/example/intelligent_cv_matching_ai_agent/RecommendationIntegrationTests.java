package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.RecommendationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobEmbeddingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Native pgvector(1024), real Flyway schema, normal HTTP authorization; only the paid provider is mocked. */
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
        "app.jobs.embedding.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false",
        "app.recommendation.top-k=1",}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class RecommendationIntegrationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    SqlStore db;

    @Autowired
    TokenService tokens;

    @Autowired
    JsonMapper json;

    @Autowired
    JobEmbeddingRepository embeddings;

    @Autowired
    JobService jobCatalog;

    @MockitoBean
    AiService ai;

    @MockitoSpyBean
    RecommendationRepository retrieval;

    private UUID owner, cv;

    private String authorization;

    @BeforeEach
    void fixture() {
        reset(ai);
        clearInvocations(retrieval);
        when(ai.provider()).thenReturn("gemini");
        when(ai.embeddingProvider()).thenReturn("gemini");
        when(ai.embeddingModel()).thenReturn("fixture-embedding");
        when(ai.embeddingDimensions()).thenReturn(1024);
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(ai.embed(anyString())).thenReturn(vector(0));
        when(ai.cacheFingerprint(anyString())).thenReturn("test-prompt-v1");
        db.update("UPDATE market_job_posts SET status='REMOVED' WHERE status='ACTIVE'");
        owner = account();
        authorization = "Bearer " + tokens.issue(owner).get("accessToken");
        cv = resume(owner, "READY", "Java");
    }

    @Test
    void catalogProjectsSkillsAndExperienceWithoutMatching() {
        UUID id = job("Java catalog role", "Java required");
        db.update("UPDATE market_job_posts SET parsed_seniority='Senior',salary_text='35 - 50 triệu' WHERE id=?", id);
        var row = jobCatalog.jobs("Java catalog role", "", 20, 0).getFirst();
        assertEquals("Senior", row.get("parsed_seniority"));
        assertEquals("Java", object(array(row.get("skills")).getFirst()).get("name"));
        assertEquals(50000000, number(row.get("monthly_salary_max_vnd"), 0));
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void ruleRecommendationsWorkWhileTheCandidateHasAnActiveAiTask() throws Exception {
        UUID matchingJob = job("Java developer", "Java required");
        indexed(matchingJob, "fixture-embedding", vector(0));
        db.update(
            "INSERT INTO candidate_ai_requests(user_id,kind,status,lease_until) "
                + "VALUES(?,'ROADMAP','RUNNING',now()+interval '5 minutes')",
            owner
        );
        clearInvocations(ai);

        var response = request(
            fields("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 10, "mode", "RECOMMENDATION_RULES")
        );

        var result = object(array(response.get("results")).getFirst());
        assertEquals(matchingJob.toString(), text(result.get("market_job_id")));
        assertEquals(
            "RECOMMENDATION_RULE_COVERAGE",
            object(object(result.get("match_reason_json")).get("assessment")).get("score_kind")
        );
        verifyNoInteractions(ai);
    }

    @Test
    void onlyCurrentCompatibleVectorsAreRanked() throws Exception {
        UUID distant = job("Textual Java exact match", "Java");
        UUID nearest = job("Semantic neighbor", "Java");
        indexed(distant, "fixture-embedding", vector(1));
        indexed(nearest, "fixture-embedding", vector(0));
        // A job with an incompatible embedding is excluded.
        UUID wrongModel = job("Wrong model", "Java");
        indexed(wrongModel, "different-model", vector(0));
        UUID stale = job("Stale vector", "Java");
        indexed(stale, "fixture-embedding", vector(0));
        db.update("UPDATE market_job_posts SET raw_content_hash=? WHERE id=?", FlowSupport.hash("new content"), stale);
        UUID expired = job("Expired", "Java");
        indexed(expired, "fixture-embedding", vector(0));
        db.update("UPDATE market_job_posts SET valid_through=now()-interval '1 day' WHERE id=?", expired);

        var response = recommend();
        assertEquals(2, array(response.get("results")).size());
        var row = array(response.get("results")).stream().map(value -> object(value))
            .filter(result -> nearest.toString().equals(result.get("market_job_id"))).findFirst().orElseThrow();
        assertEquals(1.0, number(row.get("final_score"), 0), 0.00001);
        assertNull(row.get("llm_rerank_score"));
        verify(ai, never()).generate(anyString(), anyMap());
        assertEquals(1.0, number(row.get("vector_similarity_score"), 0), 0.00001);
        assertEquals("PGVECTOR_COSINE", object(row.get("match_reason_json")).get("retrieval"));
        assertEquals("PARTIAL_INDEX", object(response.get("recommendation")).get("semantic_status"));
        assertEquals(2, number(object(response.get("recommendation")).get("compatible_vectors"), 0));
        verify(retrieval).semanticCandidates(eq("gemini"), eq("fixture-embedding"), eq(1024), anyString(), isNull());
    }

    @Test
    void salaryRangeAppliesOnlyToCareerProfileRecommendations() throws Exception {
        db.update(
            "INSERT INTO candidate_profiles(user_id,target_job_title,expected_salary,salary_currency) "
                + "VALUES(?,'Java developer',20000000,'VND')",
            owner
        );
        UUID within = job("Java developer", "Java");
        UUID above = job("Senior Java developer", "Java");
        UUID below = job("Junior Java developer", "Java");
        db.update("UPDATE market_job_posts SET salary_text='21000000 VND' WHERE id=?", within);
        db.update("UPDATE market_job_posts SET salary_text='30000000 VND' WHERE id=?", above);
        db.update("UPDATE market_job_posts SET salary_text='15000000 VND' WHERE id=?", below);
        indexed(within, "fixture-embedding", vector(0));
        indexed(above, "fixture-embedding", vector(0));
        indexed(below, "fixture-embedding", vector(0));

        assertEquals(3, array(recommend().get("results")).size());
        var profile = request(fields("basis", "CAREER_PROFILE", "limit", 50, "mode", "RECOMMENDATION"));
        var results = array(profile.get("results"));
        assertEquals(1, results.size());
        assertEquals(within.toString(), object(results.getFirst()).get("market_job_id"));
    }

    @Test
    void candidateAndResultCachesInvalidateOnCvJobAndModelChanges() throws Exception {
        UUID job = job("Java developer", "Java");
        indexed(job, "fixture-embedding", vector(0));
        assertEquals(false, recommend().get("cached"));
        assertEquals(true, recommend().get("cached"));
        verify(ai, times(1)).embed(anyString());
        db.update("UPDATE resumes SET structured_data=? WHERE id=?", db.json(data("Python")), cv);
        assertEquals(false, recommend().get("cached"));
        verify(ai, times(2)).embed(anyString());
        db.update("UPDATE market_job_posts SET company_name='Changed company' WHERE id=?", job);
        assertEquals(false, recommend().get("cached"));
        verify(ai, times(2)).embed(anyString());
        when(ai.embeddingModel()).thenReturn("model-next");
        indexed(job, "model-next", vector(0));
        assertEquals(false, recommend().get("cached"));
        verify(ai, times(3)).embed(anyString());
    }

    @Test
    void embedding503ReturnsNoRecommendationsAndDoesNotCacheTheOutage() throws Exception {
        indexed(job("Java developer", "Java"), "fixture-embedding", vector(0));
        when(ai.embed(anyString())).thenThrow(new ApiException(503, "Provider unavailable"));
        var response = recommend();
        var metadata = object(response.get("recommendation"));
        assertTrue(array(response.get("results")).isEmpty());
        assertEquals(false, metadata.get("vector_used"));
        assertEquals("PROVIDER_ERROR", metadata.get("semantic_status"));
        assertEquals(false, metadata.get("cacheable"));
        recommend();
        verify(ai, times(2)).embed(anyString());
        verify(ai, never()).generate(anyString(), anyMap());
    }

    @Test
    void missingCompatibleVectorsHaveVisibleStatusAndDoNotSpendEmbeddingTokens() throws Exception {
        job("Unindexed", "Java");
        var response = recommend();
        assertEquals("NO_COMPATIBLE_VECTORS", object(response.get("recommendation")).get("semantic_status"));
        assertEquals(false, object(response.get("recommendation")).get("vector_used"));
        assertTrue(array(response.get("results")).isEmpty());
        verify(ai, never()).embed(anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 6, 10, 15})
    void matchesEveryEligibleJobBeforeReturningAtMostTen(
        int count)
        throws Exception {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            UUID id = job("Java position " + index, "Java required");
            indexed(id, "fixture-embedding", vector(0));
            scores.put(id, 1.0);
        }
        UUID expired = job("Expired Java position", "Java required");
        db.update("UPDATE market_job_posts SET valid_through=now()-interval '1 day' WHERE id=?", expired);
        var response = recommend();
        var actualIds =
            array(response.get("results")).stream().map(row -> text(object(row).get("market_job_id"))).toList();
        var expectedIds = scores.entrySet().stream()
            .sorted(
                Map.Entry.<UUID, Double>comparingByValue().reversed().thenComparing(entry -> entry.getKey().toString())
            ).limit(10).map(entry -> entry.getKey().toString()).toList();
        assertEquals(expectedIds, actualIds);
        assertEquals(count, number(object(response.get("recommendation")).get("ranked_jobs"), 0));
    }

    @Test
    void noConfirmedCvIsRejectedAndCareerProfileCanBeSelectedExplicitly() throws Exception {
        db.update("UPDATE resumes SET status='DRAFT' WHERE id=?", cv);
        mvc.perform(
            post("/api/v1/matching").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        fields("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 50, "mode", "RECOMMENDATION")
                    )
                )
        ).andExpect(status().isConflict());
        db.update(
            ("INSERT INTO candidate_profiles(user_id,target_job_title,"
                + "profile_summary) VALUES(?,'Java developer','Built Java APIs')"),
            owner
        );
        var response = request(fields("basis", "CAREER_PROFILE", "limit", 50, "mode", "RECOMMENDATION"));
        assertEquals("EMPTY_CATALOG", object(response.get("recommendation")).get("semantic_status"));
        assertTrue(array(response.get("results")).isEmpty());
    }

    @Test
    void aUserCannotUseAnotherUsersCvOrCandidateEmbeddingCache() throws Exception {
        UUID other = account(), otherCv = resume(other, "READY", "Java");
        indexed(job("Java developer", "Java"), "fixture-embedding", vector(0));
        recommend();
        authorization = "Bearer " + tokens.issue(other).get("accessToken");
        cv = otherCv;
        recommend();
        verify(ai, times(2)).embed(anyString());
        mvc.perform(
            post("/api/v1/matching").header(
                "Authorization",
                "Bearer " + tokens.issue(owner).get("accessToken")
            ).contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(
                    fields("basis", "SELECTED_CV", "resumeIds", List.of(otherCv), "mode", "RECOMMENDATION", "limit", 50)
                )
            )
        ).andExpect(status().isNotFound());
    }

    @Test
    void newInputProvenancePreservesOldVectorsAndBackfillIsModelAndVersionAware() {
        UUID job = job("Java developer", "Java");
        indexed(job, "fixture-embedding", vector(0));
        assertTrue(
            embeddings.backfillCandidates("gemini", "fixture-embedding", JobEmbeddingSupport.VERSION, 50).isEmpty()
        );
        assertEquals(
            1,
            embeddings.backfillCandidates("gemini", "different-model", JobEmbeddingSupport.VERSION, 50).size()
        );
        String rawHash =
            text(db.one("SELECT raw_content_hash FROM market_job_posts WHERE id=?", job).get("raw_content_hash"));
        embeddings.replaceCurrentEmbedding(
            job,
            "gemini",
            "fixture-embedding",
            "d".repeat(64),
            Arrays.toString(vector(1)),
            rawHash,
            JobEmbeddingSupport.VERSION
        );
        assertEquals(2, number(db.scalar("SELECT count(*) FROM market_job_embeddings WHERE market_job_id=?", job), 0));
        assertEquals(
            1,
            number(
                db.scalar(
                    ("SELECT count(*) FROM market_job_embeddings WHERE market_job_id=? AND " + "is_current"),
                    job
                ),
                0
            )
        );
    }

    @Test
    void invalidDimensionsAndNonFiniteVectorsCannotEnterTheSearch() throws Exception {
        indexed(job("Java developer", "Java"), "fixture-embedding", vector(0));
        when(ai.embed(anyString())).thenReturn(new float[10]);
        var response = recommend();
        assertEquals("PROVIDER_ERROR", object(response.get("recommendation")).get("semantic_status"));
        verify(retrieval, never()).semanticCandidates(anyString(), anyString(), anyInt(), anyString(), any());
        assertEquals(0, number(db.scalar("SELECT count(*) FROM matching_evidence_vectors WHERE user_id=?", owner), 0));
    }

    private Map<String, Object> recommend() throws Exception {
        return request(fields("basis", "SELECTED_CV", "resumeIds", List.of(cv), "limit", 50, "mode", "RECOMMENDATION"));
    }

    private Map<String, Object> request(
        Map<String, Object> input)
        throws Exception {
        String body = mvc.perform(
            post("/api/v1/matching").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))
        ).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    private UUID account() {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@recommend.test",
                "full_name",
                "Recommendation test",
                "password_hash",
                "test-only-unusable-hash",
                "role",
                "CANDIDATE",
                "status",
                "ACTIVE"
            )
        );
    }

    private UUID resume(
        UUID user,
        String status,
        String skill) {
        return db.insert(
            "resumes",
            fields(
                "user_id",
                user,
                "title",
                "Confirmed CV",
                "source",
                "MANUAL",
                "status",
                status,
                "structured_data",
                db.json(data(skill))
            )
        );
    }

    private Map<String, Object> data(
        String skill) {
        return fields(
            "skills",
            List.of(fields("name", skill)),
            "projects",
            List.of(fields("name", "API", "role", "Backend developer", "description", "Built " + skill + " APIs"))
        );
    }

    private UUID job(
        String title,
        String requirements) {
        UUID id = db.insert(
            "market_job_posts",
            fields(
                "source_site",
                "ITVIEC",
                "raw_content_hash",
                FlowSupport.hash(UUID.randomUUID().toString()),
                "job_title",
                title,
                "company_name",
                "Fixture",
                "role_category",
                "Backend",
                "raw_content",
                "Requirements:\n" + requirements,
                "parsed_requirements",
                requirements,
                "status",
                "ACTIVE"
            )
        );
        db.update(
            ("INSERT INTO market_job_skills(market_job_id,skill_name,"
                + "normalized_skill,is_required) VALUES(?,'Java','Java',true)"),
            id
        );
        return id;
    }

    private void indexed(
        UUID job,
        String model,
        float[] vector) {
        var source = embeddings.findJobForUpdate(job).orElseThrow();
        embeddings.replaceCurrentEmbedding(
            job,
            "gemini",
            model,
            JobEmbeddingSupport.inputHash(source),
            Arrays.toString(vector),
            text(source.get("raw_content_hash")),
            JobEmbeddingSupport.VERSION
        );
    }

    private static float[] vector(
        int axis) {
        float[] vector = new float[1024];
        vector[axis] = 1;
        return vector;
    }
}
