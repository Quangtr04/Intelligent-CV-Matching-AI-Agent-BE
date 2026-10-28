package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the cache fingerprint SQL in an isolated schema, without a model or application database. */
class MatchingCatalogCacheIntegrationTests {

    private static PostgreSQLContainer container;

    private static DriverManagerDataSource database;

    private JdbcTemplate jdbc;

    private MatchingRepository repository;

    private String schema;

    private final UUID job = UUID.randomUUID();

    private final UUID alias = UUID.randomUUID();

    @BeforeAll
    static void connectToTestDatabase() {
        String url = System.getenv("EDUNEXUS_TEST_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            database = new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_USER", "edunexus_test"),
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_PASSWORD", "")
            );
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "An isolated PostgreSQL database is required");
        container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        database =
            new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @AfterAll
    static void stopContainer() {
        if (container != null)
            container.stop();
    }

    @BeforeEach
    void createFixture() {
        schema = "matching_response_cache_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource scoped =
            new DriverManagerDataSource(database.getUrl(), database.getUsername(), database.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        scoped.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(scoped);
        jdbc.setQueryTimeout(5);
        jdbc.execute("""
            CREATE TABLE market_job_posts(
                id uuid PRIMARY KEY,raw_content_hash text,job_title text,company_name text,location text,
                salary_text text,valid_through timestamptz,source_url text,source_site text,status text
            )
            """);
        jdbc.execute("""
            CREATE TABLE market_job_sources(
                id uuid PRIMARY KEY,market_job_id uuid REFERENCES market_job_posts(id),
                source_url text,source_site text,status text,valid_through timestamptz,last_seen_at timestamptz
            )
            """);
        jdbc.update(
            "INSERT INTO market_job_posts VALUES(?,?,?,?,?,?,now()+interval '2 days',?,?,?)",
            job,
            "unchanged-raw-hash",
            "Java developer",
            "Example",
            "HCM",
            "20-30 million VND",
            "https://example.test/primary",
            "PRIMARY",
            "ACTIVE"
        );
        jdbc.update(
            "INSERT INTO market_job_sources VALUES(?,?,?,?,'ACTIVE',now()+interval '1 day',now())",
            alias,
            job,
            "https://example.test/alias",
            "ALIAS"
        );
        repository = new MatchingRepository(new SqlStore(jdbc));
    }

    @AfterEach
    void dropOnlyFixtureSchema() {
        if (schema != null)
            new JdbcTemplate(database).execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"job_title", "company_name", "location", "salary_text", "source_url"})
    void displayedMetadataInvalidatesCacheWithoutChangingTheJdHash(
        String column) {
        Object before = repository.readMarketJobPostsByStatus();
        jdbc.update("UPDATE market_job_posts SET " + column + "=? WHERE id=?", "Changed display value", job);
        assertNotEquals(before, repository.readMarketJobPostsByStatus());
    }

    @Test
    void currentSourceAliasChangesAndExpirationInvalidateCache() {
        Object before = repository.readMarketJobPostsByStatus();
        jdbc.update(
            "UPDATE market_job_sources SET source_url=?,source_site=? WHERE id=?",
            "https://example.test/new-alias",
            "NEW_ALIAS",
            alias
        );
        Object changed = repository.readMarketJobPostsByStatus();
        assertNotEquals(before, changed);
        jdbc.update("UPDATE market_job_sources SET valid_through=now()-interval '1 second' WHERE id=?", alias);
        assertNotEquals(changed, repository.readMarketJobPostsByStatus());
    }

    @Test
    void expirationAndStatusRemoveJobsFromTheCacheFingerprint() {
        Object active = repository.readMarketJobPostsByStatus();
        jdbc.update("UPDATE market_job_posts SET status='EXPIRED' WHERE id=?", job);
        Object inactive = repository.readMarketJobPostsByStatus();
        assertNotEquals(active, inactive);
        jdbc.update("UPDATE market_job_posts SET job_title='Ignored inactive title' WHERE id=?", job);
        assertEquals(inactive, repository.readMarketJobPostsByStatus());
        jdbc.update(
            "UPDATE market_job_posts SET status='ACTIVE',valid_through=now()-interval '1 second' WHERE id=?",
            job
        );
        assertEquals(inactive, repository.readMarketJobPostsByStatus());
    }
}
