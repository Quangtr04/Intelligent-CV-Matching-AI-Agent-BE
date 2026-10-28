package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobEmbeddingRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

/** Uses only an explicitly configured disposable PostgreSQL database; no model provider or vectors are required. */
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
        "app.jobs.embedding.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@EnabledIfEnvironmentVariable(named = "EDUNEXUS_TEST_DATABASE_URL", matches = ".+")
@Transactional
class JobEmbeddingRepositoryIntegrationTests {

    @Autowired
    SqlStore sql;

    @Autowired
    JobEmbeddingRepository db;

    @Autowired
    JobRepository publicJobs;

    @Autowired
    CareerToolsRepository careerTools;

    @Autowired
    MatchingRepository matching;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID job;

    private UUID actor;

    private UUID replacementActor;

    private String hash;

    private String model;

    private TransactionTemplate transactions;

    @BeforeEach
    void fixtures() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            actor = administrator();
            replacementActor = administrator();
            hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            model = "queue-" + UUID.randomUUID();
            job = sql.insert(
                "market_job_posts",
                fields(
                    "source_site",
                    "ITVIEC",
                    "source_url",
                    "https://itviec.com/it-jobs/queue-" + UUID.randomUUID(),
                    "raw_content_hash",
                    hash,
                    "job_title",
                    "Queue SQL Test",
                    "role_category",
                    "Backend",
                    "raw_content",
                    "A disposable job used to verify durable embedding claims",
                    "status",
                    "ACTIVE"
                )
            );
        });
    }

    private UUID administrator() {
        return sql.insert(
            "users",
            fields(
                "email",
                "queue-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Queue SQL Test",
                "auth_provider",
                "EMAIL",
                "password_hash",
                "not-used-by-these-tests",
                "role",
                "ADMIN",
                "status",
                "ACTIVE",
                "email_verified_at",
                OffsetDateTime.now()
            )
        );
    }

    @AfterEach
    void cleanup() {
        if (job == null)
            return;
        transactions.executeWithoutResult(status -> {
            sql.update("DELETE FROM vector_indexing_jobs WHERE entity_type='JOB' AND entity_id=?", job);
            sql.update("DELETE FROM market_job_embeddings WHERE market_job_id=?", job);
            sql.update("DELETE FROM job_match_results WHERE market_job_id=?", job);
            sql.update("DELETE FROM job_matching_runs WHERE user_id IN (?,?)", actor, replacementActor);
            sql.update("DELETE FROM market_job_sources WHERE market_job_id=?", job);
            sql.update("DELETE FROM market_job_skills WHERE market_job_id=?", job);
            sql.update("DELETE FROM market_job_posts WHERE id=?", job);
            sql.update("DELETE FROM users WHERE id IN (?,?)", actor, replacementActor);
        });
    }

    private UUID enqueue(
        UUID requestedBy) {
        return db.enqueue(job, hash, requestedBy, "test", model, "QUEUED", null);
    }

    @Test
    void salaryFilteredCandidatesKeepJobContentSkillsAndTheActiveSource() {
        assertEquals("", matching.readMarketJobEmbeddingsByStatus("test", model));
        String original = primaryUrl();
        String active = "https://www.topcv.vn/viec-lam/salary-candidate-" + UUID.randomUUID() + ".html";
        source(original, "ITVIEC", OffsetDateTime.now().minusDays(1));
        source(active, "TOPCV", OffsetDateTime.now().plusDays(1));
        sql.update(
            "UPDATE market_job_posts SET company_name='Matching Company',location='Ha Noi',"
                + "salary_text='1000 USD/month' WHERE id=?",
            job
        );
        sql.insert(
            "market_job_skills",
            fields("market_job_id", job, "skill_name", "Java", "normalized_skill", "java", "is_required", true)
        );

        var candidates = matching.listMarketJobSkillsByIds("{" + job + "," + UUID.randomUUID() + "}");
        assertEquals(1, candidates.size());
        Map<String, Object> candidate = candidates.getFirst();
        assertEquals(job, candidate.get("id"));
        assertEquals("Queue SQL Test", candidate.get("job_title"));
        assertEquals("Matching Company", candidate.get("company_name"));
        assertEquals("Ha Noi", candidate.get("location"));
        assertEquals("1000 USD/month", candidate.get("salary_text"));
        assertEquals(hash, candidate.get("raw_content_hash"));
        assertEquals("A disposable job used to verify durable embedding claims", candidate.get("raw_content"));
        assertEquals(original, candidate.get("original_source_url"));
        assertEquals(active, candidate.get("source_url"));
        assertEquals("TOPCV", candidate.get("source_site"));
        var skills = com.example.intelligent_cv_matching_ai_agent.util.shared.Values.array(candidate.get("skills"));
        assertEquals(1, skills.size());
        assertEquals("java", ((Map<?, ?>) skills.getFirst()).get("name"));
        assertEquals(true, ((Map<?, ?>) skills.getFirst()).get("required"));

        sql.update("UPDATE market_job_posts SET valid_through=now()-interval '1 minute' WHERE id=?", job);
        assertTrue(matching.listMarketJobSkillsByIds("{" + job + "}").isEmpty());
        sql.update("UPDATE market_job_posts SET valid_through=NULL,status='REMOVED' WHERE id=?", job);
        assertTrue(matching.listMarketJobSkillsByIds("{" + job + "}").isEmpty());
    }

    @Test
    void duplicateFingerprintKeepsOneTaskAndTerminalRetryRefreshesItsOwner() {
        UUID id = enqueue(actor);
        assertEquals(id, enqueue(replacementActor));
        assertEquals(
            1,
            ((Number) sql
                .scalar("SELECT count(*) FROM vector_indexing_jobs WHERE entity_type='JOB' AND entity_id=?", job))
                .intValue()
        );
        assertEquals(
            actor,
            sql.one("SELECT requested_by FROM vector_indexing_jobs WHERE id=?", id).get("requested_by")
        );
        UUID token = UUID.randomUUID();
        Map<String, Object> claimed = db.claimById(id, "test", model, token, 300).orElseThrow();
        assertEquals(1, ((Number) claimed.get("attempt_count")).intValue());
        sql.update(
            ("UPDATE vector_indexing_jobs SET attempt_count=3,lease_until=now()-interval '1 second' WHERE id=?"),
            id
        );
        assertEquals("FAILED", db.claimById(id, "test", model, UUID.randomUUID(), 300).orElseThrow().get("status"));

        assertEquals(id, enqueue(replacementActor));
        Map<String, Object> retried = sql.one("SELECT * FROM vector_indexing_jobs WHERE id=?", id);
        assertEquals("QUEUED", retried.get("status"));
        assertEquals(replacementActor, retried.get("requested_by"));
        assertEquals(0, ((Number) retried.get("attempt_count")).intValue());
        assertNull(retried.get("lease_token"));
        assertNull(retried.get("lease_until"));
        assertNull(retried.get("error_message"));
    }

    @Test
    void retryDelayIsRespectedAndThirdFailureIsTerminal() {
        UUID id = enqueue(actor);
        UUID token = UUID.randomUUID();
        assertEquals(
            1,
            ((Number) db.claimById(id, "test", model, token, 300).orElseThrow().get("attempt_count")).intValue()
        );
        assertEquals(1, db.retryOrFail(id, token, "Provider unavailable", 30));
        assertEquals("QUEUED", db.task(id).get("status"));
        assertTrue(db.claimById(id, "test", model, UUID.randomUUID(), 300).isEmpty());

        for (int attempt = 2; attempt <= 3; attempt++) {
            sql.update("UPDATE vector_indexing_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
            token = UUID.randomUUID();
            Map<String, Object> claimed = db.claimById(id, "test", model, token, 300).orElseThrow();
            assertEquals(attempt, ((Number) claimed.get("attempt_count")).intValue());
            assertEquals(1, db.retryOrFail(id, token, "Provider unavailable", 60));
        }

        assertEquals("FAILED", db.task(id).get("status"));
        assertEquals(3, ((Number) db.task(id).get("attempt_count")).intValue());
        assertEquals("Provider unavailable", db.task(id).get("error_message"));
        assertTrue(db.claimById(id, "test", model, UUID.randomUUID(), 300).isEmpty());
    }

    @Test
    void leaseReclamationFencesCompletionCancellationAndRefundStateFromTheOldWorker() {
        UUID id = enqueue(actor);
        UUID oldToken = UUID.randomUUID();
        db.claimById(id, "test", model, oldToken, 300).orElseThrow();
        assertTrue(db.findLeaseForUpdate(id, oldToken).isPresent());
        sql.update("UPDATE vector_indexing_jobs SET lease_until=now()-interval '1 second' WHERE id=?", id);
        UUID newToken = UUID.randomUUID();
        Map<String, Object> reclaimed = db.claimById(id, "test", model, newToken, 300).orElseThrow();

        assertEquals(2, ((Number) reclaimed.get("attempt_count")).intValue());
        assertTrue(db.findLeaseForUpdate(id, oldToken).isEmpty());
        assertEquals(0, db.complete(id, oldToken));
        assertEquals(0, db.cancel(id, oldToken, "Late cancellation"));
        assertEquals(0, db.retryOrFail(id, oldToken, "Late failure", 30));
        assertEquals(1, db.complete(id, newToken));
        assertEquals("COMPLETED", db.task(id).get("status"));
    }

    @Test
    void softDeletedAdministratorCannotAuthorizeNewWork() {
        assertTrue(db.isActiveAdministrator(actor));
        sql.update("UPDATE users SET deleted_at=now() WHERE id=?", actor);
        assertFalse(db.isActiveAdministrator(actor));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentLockedTaskIsSkippedRatherThanClaimedTwice() throws Exception {
        UUID id = transactions.execute(status -> enqueue(actor));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                sql.one("SELECT id FROM vector_indexing_jobs WHERE id=? FOR UPDATE", id);
                locked.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                Optional<Map<String, Object>> skipped =
                    transactions.execute(status -> db.claimById(id, "test", model, UUID.randomUUID(), 300));
                assertNotNull(skipped);
                assertTrue(skipped.isEmpty());
            } finally {
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
        }

        Map<String, Object> claimed =
            transactions.execute(status -> db.claimById(id, "test", model, UUID.randomUUID(), 300).orElseThrow());
        assertEquals("PROCESSING", claimed.get("status"));
        assertEquals(1, ((Number) claimed.get("attempt_count")).intValue());
    }

    private String primaryUrl() {
        return sql.one("SELECT source_url FROM market_job_posts WHERE id=?", job).get("source_url").toString();
    }

    private void source(
        String url,
        String site,
        OffsetDateTime deadline) {
        sql.insert(
            "market_job_sources",
            fields(
                "market_job_id",
                job,
                "source_site",
                site,
                "source_url",
                url,
                "content_hash",
                hash,
                "valid_through",
                deadline,
                "status",
                "ACTIVE"
            )
        );
    }

    @Test
    void expiredPrimaryUrlFallsBackToActiveAliasInPublicDetailToolsAndMatchingHistory() {
        String primary = primaryUrl();
        String active = "https://www.topcv.vn/viec-lam/queue-alias-" + UUID.randomUUID() + ".html";
        source(primary, "ITVIEC", OffsetDateTime.now().minusDays(1));
        source(active, "TOPCV", OffsetDateTime.now().plusDays(1));

        Map<String, Object> detail = publicJobs.getMarketJobPostsById(job);
        assertEquals(active, detail.get("source_url"));
        assertEquals("TOPCV", detail.get("source_site"));
        assertEquals(primary, detail.get("original_source_url"));
        assertEquals(active, careerTools.getMarketJobPostsById(job).get("source_url"));
        assertEquals(
            active,
            publicJobs.listMarketJobPostsByStatus("%Queue SQL Test%", "%", 100, 0).stream()
                .filter(row -> job.equals(row.get("id"))).findFirst().orElseThrow().get("source_url")
        );
        UUID run =
            sql.insert("job_matching_runs", fields("user_id", actor, "basis", "CAREER_PROFILE", "status", "COMPLETED"));
        sql.insert(
            "job_match_results",
            fields("matching_run_id", run, "market_job_id", job, "final_score", 0.8, "rank_position", 1)
        );
        assertEquals(active, matching.listJobMatchResultsByMatchingRunId(run).getFirst().get("source_url"));
    }

    @Test
    void primarySourceIsPreferredWhenStillActiveAndExpiredCanonicalJobsAreHiddenBeforeMaintenance() {
        String primary = primaryUrl();
        source(primary, "ITVIEC", OffsetDateTime.now().plusDays(1));
        source("https://www.topcv.vn/viec-lam/newer-alias-" + UUID.randomUUID() + ".html", "TOPCV", null);
        assertEquals(primary, publicJobs.getMarketJobPostsById(job).get("source_url"));
        assertEquals(primary, careerTools.getMarketJobPostsById(job).get("source_url"));
        sql.update("UPDATE market_job_posts SET valid_through=now()-interval '1 second' WHERE id=?", job);

        assertThrows(
            com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException.class,
            () -> publicJobs.getMarketJobPostsById(job)
        );
        assertThrows(
            com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException.class,
            () -> careerTools.getMarketJobPostsById(job)
        );
    }

    @Test
    void knownExpiredSourceNeverUsesTheLegacyFallback() {
        source(primaryUrl(), "ITVIEC", OffsetDateTime.now().minusDays(1));

        assertNull(publicJobs.getMarketJobPostsById(job).get("source_url"));
        assertNull(careerTools.getMarketJobPostsById(job).get("source_url"));
    }

    @Test
    void unmappedLegacyJobKeepsItsOriginalSourceUrl() {
        assertEquals(primaryUrl(), publicJobs.getMarketJobPostsById(job).get("source_url"));
        assertEquals(primaryUrl(), careerTools.getMarketJobPostsById(job).get("source_url"));
    }
}
