package com.example.intelligent_cv_matching_ai_agent.jobs;

import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/** Real JDBC ingestion checks; requires an explicitly supplied disposable database and rolls back each test. */
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
        "app.ocr.enabled=false"}
)
@EnabledIfEnvironmentVariable(named = "EDUNEXUS_TEST_DATABASE_URL", matches = ".+")
@Transactional
class JobIngestionIntegrationTests {

    private static final OffsetDateTime POSTED = OffsetDateTime.parse("2026-10-01T00:00:00+07:00");

    private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2099-12-31T00:00:00+07:00");

    @Autowired
    JobService jobs;

    @Autowired
    SqlStore db;

    @Autowired
    JobCrawlRepository crawler;

    private UUID administrator;

    private String company;

    private String sourceA;

    private String sourceB;

    @BeforeEach
    void fixtures() {
        UUID identity = UUID.randomUUID();
        company = "Ingestion Company " + identity;
        sourceA = "https://itviec.com/it-jobs/java-ingestion-" + identity + "-1234";
        sourceB = "https://www.topcv.vn/viec-lam/java-ingestion-" + identity + "/567890.html";
        administrator = user("ADMIN");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                administrator.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualImportPersistsNormalizedMetadataAndRepeatedImportCreatesOnlyOneEmbeddingTask() {
        ImportJobRequest input = posting("ITVIEC", sourceA, baseDescription(), POSTED, DEADLINE);
        UUID id = id(jobs.importJob(input));
        Map<String, Object> stored = db.one("SELECT * FROM market_job_posts WHERE id=?", id);
        assertEquals("Ho Chi Minh", stored.get("location"));
        assertEquals("FULL_TIME", stored.get("employment_type"));
        assertEquals("Required Java and SpringBoot experience.", stored.get("parsed_requirements"));
        assertEquals(
            "Spring Boot",
            db.scalar(
                "SELECT skill_name FROM market_job_skills WHERE market_job_id=? AND normalized_skill=?",
                id,
                "spring boot"
            )
        );
        assertEquals(
            true,
            db.scalar(
                "SELECT is_required FROM market_job_skills WHERE market_job_id=? AND normalized_skill=?",
                id,
                "spring boot"
            )
        );
        assertNotNull(stored.get("valid_through"));
        assertEquals(id, id(jobs.importJob(input)));
        assertEquals(1L, count("SELECT count(*) FROM market_job_sources WHERE market_job_id=?", id));
        assertEquals(
            1L,
            count(
                "SELECT count(*) FROM vector_indexing_jobs WHERE entity_type='JOB' AND entity_id=? AND status='QUEUED'",
                id
            )
        );
    }

    @Test
    void crossSourceJobKeepsActiveAliasAndVectorUntilEverySourceExpires() {
        UUID id = id(jobs.importJob(posting("ITVIEC", sourceA, baseDescription(), POSTED, DEADLINE)));
        UUID duplicate = id(
            jobs.importJob(posting("TOPCV", sourceB, baseDescription(), POSTED.plusDays(1), DEADLINE.plusMonths(1)))
        );
        assertEquals(id, duplicate);
        assertEquals(2L, count("SELECT count(*) FROM market_job_sources WHERE market_job_id=?", id));
        db.insert(
            "market_job_embeddings",
            fields(
                "market_job_id",
                id,
                "provider",
                "local-test",
                "model",
                "local-test",
                "embedding",
                null,
                "content_hash",
                db.scalar("SELECT raw_content_hash FROM market_job_posts WHERE id=?", id),
                "is_current",
                true
            )
        );

        expire("ITVIEC", sourceA);
        assertEquals("ACTIVE", status(id));
        assertEquals(true, db.scalar("SELECT is_current FROM market_job_embeddings WHERE market_job_id=?", id));
        assertEquals(sourceB, jobs.job(id).get("source_url"));

        expire("TOPCV", sourceB);
        assertEquals("EXPIRED", status(id));
        assertEquals(false, db.scalar("SELECT is_current FROM market_job_embeddings WHERE market_job_id=?", id));
        assertEquals(404, assertThrows(ApiException.class, () -> jobs.job(id)).status());
    }

    @Test
    void revisionAndReversionRelinkSourceWithoutLeavingAnActiveOrphanVersion() {
        ImportJobRequest original = posting("ITVIEC", sourceA, baseDescription(), POSTED, DEADLINE);
        UUID canonical = id(jobs.importJob(original));
        assertEquals(
            canonical,
            id(jobs.importJob(posting("TOPCV", sourceB, baseDescription(), POSTED.plusDays(1), DEADLINE.plusMonths(1))))
        );

        UUID revision = id(
            jobs.importJob(
                posting(
                    "ITVIEC",
                    sourceA,
                    baseDescription() + " This revision adds an explicit new delivery responsibility.",
                    POSTED,
                    DEADLINE
                )
            )
        );
        assertNotEquals(canonical, revision);
        assertEquals("ACTIVE", status(canonical));
        assertEquals("ACTIVE", status(revision));
        assertEquals(revision, db.scalar("SELECT market_job_id FROM market_job_sources WHERE source_url=?", sourceA));

        assertEquals(canonical, id(jobs.importJob(original)));
        assertEquals("ACTIVE", status(canonical));
        assertEquals("REMOVED", status(revision));
        assertEquals(canonical, db.scalar("SELECT market_job_id FROM market_job_sources WHERE source_url=?", sourceA));
        assertEquals(
            2L,
            count("SELECT count(*) FROM market_job_sources WHERE market_job_id=? AND status='ACTIVE'", canonical)
        );
        assertEquals(
            0L,
            count("SELECT count(*) FROM market_job_sources WHERE market_job_id=? AND status='ACTIVE'", revision)
        );
    }

    @Test
    void expiredPostingAndNonAdministratorSchedulerActorAreRejectedWithoutCreatingJobs() {
        ApiException expired = assertThrows(
            ApiException.class,
            () -> jobs.importJob(
                posting("ITVIEC", sourceA, baseDescription(), POSTED, OffsetDateTime.parse("2000-01-01T00:00:00Z"))
            )
        );
        assertEquals(400, expired.status());
        UUID candidate = user("CANDIDATE");
        ApiException forbidden = assertThrows(
            ApiException.class,
            () -> jobs.importJobAs(posting("ITVIEC", sourceA, baseDescription(), POSTED, DEADLINE), candidate)
        );
        assertEquals(403, forbidden.status());
        assertEquals(0L, count("SELECT count(*) FROM market_job_posts WHERE company_name=?", company));
        assertEquals(0L, count("SELECT count(*) FROM market_job_sources WHERE source_url=?", sourceA));
    }

    private ImportJobRequest posting(
        String site,
        String url,
        String description,
        OffsetDateTime posted,
        OffsetDateTime expires) {
        return new ImportJobRequest(
            site,
            url,
            "Java Backend Developer",
            company,
            "TP. HCM",
            "20 - 35 triệu",
            "Backend",
            description,
            List.of(new JobSkillRequest(null, "SpringBoot", true), new JobSkillRequest(null, "Java", true)),
            "Toàn thời gian",
            "Required Java and SpringBoot experience.",
            "Maintain reliable backend services.",
            "Training and development support.",
            posted,
            expires
        );
    }

    private String baseDescription() {
        return "<p>Build reliable Java and Spring Boot backend services and maintain production applications.</p>";
    }

    private UUID user(
        String role) {
        return db.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@ingestion-test.invalid",
                "full_name",
                "Ingestion Test User",
                "password_hash",
                "unused-integration-test-hash",
                "auth_provider",
                "EMAIL",
                "status",
                "ACTIVE",
                "role",
                role,
                "email_verified_at",
                POSTED
            )
        );
    }

    private void expire(
        String site,
        String url) {
        crawler.expireSource(site, url);
        crawler.updateMarketJobEmbeddingsByMarketJobId(site, url);
        crawler.updateMarketJobPostsByUpper(site, url);
    }

    private UUID id(
        Map<String, Object> row) {
        return (UUID) row.get("id");
    }

    private String status(
        UUID id) {
        return db.scalar("SELECT status FROM market_job_posts WHERE id=?", id).toString();
    }

    private long count(
        String sql,
        Object... parameters) {
        return ((Number) db.scalar(sql, parameters)).longValue();
    }
}
