package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCollectionRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "EDUNEXUS_TEST_DATABASE_URL", matches = ".+")
class JobCollectionRepositoryIntegrationTest {

    private JdbcTemplate jdbc;

    private JobCollectionRepository repository;

    private TransactionTemplate transaction;

    @BeforeEach
    void connectToExplicitDisposableTestDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("EDUNEXUS_TEST_DATABASE_URL"),
            System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_USER", "edunexus_test"),
            System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_PASSWORD", "")
        );
        jdbc = new JdbcTemplate(dataSource);
        repository = new JobCollectionRepository(new SqlStore(jdbc));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void concurrentReplicasAcquireOnlyOneLeaseAndCompletionCannotClearAnotherOwner() throws Exception {
        String name = "test-job-collection-" + UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstAttempt = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.acquireLease(name, first, Duration.ofMinutes(15), Duration.ofHours(6));
            });
            var secondAttempt = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.acquireLease(name, second, Duration.ofMinutes(15), Duration.ofHours(6));
            });
            start.countDown();
            boolean firstWon = firstAttempt.get(10, TimeUnit.SECONDS),
                secondWon = secondAttempt.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            UUID winner = firstWon ? first : second, loser = firstWon ? second : first;
            assertFalse(repository.completeLease(name, loser, "Stale worker"));
            assertTrue(repository.completeLease(name, winner, null));
            assertFalse(repository.acquireLease(name, loser, Duration.ofMinutes(15), Duration.ofHours(6)));

            jdbc.update("UPDATE job_collection_leases SET last_started_at=now()-interval '7 hours' WHERE name=?", name);
            assertTrue(repository.acquireLease(name, loser, Duration.ofMinutes(15), Duration.ofHours(6)));
            assertFalse(repository.completeLease(name, winner, "Late completion"));
        } finally {
            jdbc.update("DELETE FROM job_collection_leases WHERE name=?", name);
        }
    }

    @Test
    void expiredAliasDoesNotHideJobWithAnotherValidAliasAndExpirationInvalidatesOnlyOrphanVectors() {
        transaction.executeWithoutResult(status -> {
            createTemporaryJobTables();
            UUID expired = job(), future = job(), indefinite = job();
            source(expired, "now()-interval '1 day'");
            source(future, "now()-interval '1 day'");
            source(future, "now()+interval '1 day'");
            source(indefinite, "now()-interval '1 day'");
            source(indefinite, "NULL");

            var result = repository.expireSources(500);

            assertEquals(3, result.expiredSources());
            assertEquals(1, result.expiredJobs());
            assertEquals(1, result.invalidatedEmbeddings());
            assertEquals(
                "EXPIRED",
                jdbc.queryForObject("SELECT status FROM market_job_posts WHERE id=?", String.class, expired)
            );
            assertEquals(
                "ACTIVE",
                jdbc.queryForObject("SELECT status FROM market_job_posts WHERE id=?", String.class, future)
            );
            assertEquals(
                "ACTIVE",
                jdbc.queryForObject("SELECT status FROM market_job_posts WHERE id=?", String.class, indefinite)
            );
            assertTrue(
                Boolean.TRUE.equals(
                    jdbc.queryForObject(
                        "SELECT valid_through>now() FROM market_job_posts WHERE id=?",
                        Boolean.class,
                        future
                    )
                )
            );
            assertNull(
                jdbc.queryForObject("SELECT valid_through FROM market_job_posts WHERE id=?", Object.class, indefinite)
            );
            assertEquals(
                2,
                jdbc.queryForObject("SELECT count(*) FROM market_job_embeddings WHERE is_current=true", Integer.class)
            );
            assertEquals(new JobCollectionRepository.ExpirationResult(0, 0, 0), repository.expireSources(500));
            status.setRollbackOnly();
        });
    }

    @Test
    void expirationBatchDoesNotSkipSourceLessLegacyJobDeadline() {
        transaction.executeWithoutResult(status -> {
            createTemporaryJobTables();
            UUID legacy = job();
            jdbc.update("UPDATE market_job_posts SET valid_through=now()-interval '1 hour' WHERE id=?", legacy);
            UUID alias = job();
            source(alias, "now()-interval '1 day'");

            var first = repository.expireSources(1);
            var second = repository.expireSources(1);

            assertEquals(2, first.expiredJobs() + second.expiredJobs());
            assertEquals(1, first.expiredSources() + second.expiredSources());
            assertEquals(2, first.invalidatedEmbeddings() + second.invalidatedEmbeddings());
            status.setRollbackOnly();
        });
    }

    private void createTemporaryJobTables() {
        jdbc.execute(
            "CREATE TEMP TABLE market_job_posts(id uuid PRIMARY KEY,status varchar(32) NOT NULL,"
                + "valid_through timestamptz) ON COMMIT DROP"
        );
        jdbc.execute(
            "CREATE TEMP TABLE market_job_sources(id uuid PRIMARY KEY,market_job_id uuid NOT NULL,"
                + "status varchar(32) NOT NULL,valid_through timestamptz) ON COMMIT DROP"
        );
        jdbc.execute(
            "CREATE TEMP TABLE market_job_embeddings(id uuid PRIMARY KEY,market_job_id uuid NOT NULL,"
                + "is_current boolean NOT NULL) ON COMMIT DROP"
        );
    }

    private UUID job() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO market_job_posts(id,status) VALUES(?,'ACTIVE')", id);
        jdbc.update(
            "INSERT INTO market_job_embeddings(id,market_job_id,is_current) VALUES(?,?,true)",
            UUID.randomUUID(),
            id
        );
        return id;
    }

    private void source(
        UUID job,
        String deadlineExpression) {
        if (!java.util.Set.of("now()-interval '1 day'", "now()+interval '1 day'", "NULL")
            .contains(deadlineExpression)) {
            throw new IllegalArgumentException("Only fixed test deadline expressions are allowed");
        }
        jdbc.update(
            "INSERT INTO market_job_sources(id,market_job_id,status,valid_through) VALUES(?,?,'ACTIVE',"
                + deadlineExpression + ")",
            UUID.randomUUID(),
            job
        );
    }
}
