package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSourceSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.CrawlSettingsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCollectionRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "EDUNEXUS_LOCATION_TEST_DATABASE_URL", matches = ".+")
class CrawlSettingsRepositoryIntegrationTest {

    private JdbcTemplate administrator;

    private JdbcTemplate jdbc;

    private String schema;

    private CrawlSettingsRepository settings;

    private JobCollectionRepository collection;

    private CurrentUser current;

    private UUID actor;

    @BeforeEach
    void applyTheRealMigrationInsideOnlyTheFixtureSchema() throws Exception {
        String url = System.getenv("EDUNEXUS_LOCATION_TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_USER", "postgres");
        String password = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_PASSWORD", "");
        administrator = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        schema = "crawl_settings_test_" + UUID.randomUUID().toString().replace("-", "");
        administrator.execute("CREATE SCHEMA " + schema);
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(scopedUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE users (
                id uuid PRIMARY KEY, role text, status text, deleted_at timestamptz
            )
            """);
        jdbc.execute("""
            CREATE TABLE audit_logs (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(), actor_user_id uuid REFERENCES users(id),
                action text, entity_type text
            )
            """);
        jdbc.execute("""
            CREATE TABLE job_collection_leases (
                name text PRIMARY KEY, lease_token uuid NOT NULL, lease_until timestamptz,
                last_started_at timestamptz, last_completed_at timestamptz, last_error text
            )
            """);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils
                .executeSqlScript(connection, new ClassPathResource("db/migration/V7__admin_crawl_settings.sql"));
        }
        actor = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,role,status) VALUES(?,'ADMIN','ACTIVE')", actor);
        SqlStore store = new SqlStore(jdbc);
        settings = new CrawlSettingsRepository(store);
        collection = new JobCollectionRepository(store);
        current = mock(CurrentUser.class);
        when(current.id()).thenReturn(actor);
    }

    @AfterEach
    void dropOnlyTheRandomSchemaCreatedByThisTest() {
        if (administrator != null && schema != null) {
            administrator.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void savedSettingsSurviveANewServiceInstanceAndKeepVersionAuditAndJsonMetadata() {
        CrawlSettingsService first = service();
        assertTrue(settings.find().isEmpty());
        assertEquals("ENVIRONMENT", first.effective().configurationSource());

        CrawlSettingsResponse paused = first.update(new CrawlSettingsRequest(false, "PT1M", null, List.of()));
        assertFalse(paused.enabled());
        assertEquals(1, paused.version());
        assertNotNull(paused.updatedAt());
        CrawlSettingsService restarted = service();
        assertFalse(restarted.effective().enabled());
        assertEquals("DATABASE", restarted.effective().configurationSource());
        CrawlSettingsResponse resumed = restarted.update(
            new CrawlSettingsRequest(
                true,
                "PT2H",
                null,
                List.of(new CrawlSourceSettingsRequest("ITVIEC", "Java", "Backend", "Hà Nội", 7, 2))
            )
        );

        assertTrue(resumed.enabled());
        assertEquals(2, resumed.version());
        assertEquals("PT2H", resumed.fixedDelay());
        assertEquals(actor, resumed.actorId());
        assertEquals(actor, resumed.updatedBy());
        assertEquals("Ha Noi", resumed.sources().getFirst().location());
        assertEquals(7, resumed.sources().getFirst().limit());
        assertEquals(2, resumed.sources().getFirst().maxPages());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM job_crawl_settings", Integer.class));
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE actor_user_id=? AND action='CRAWL_SETTINGS_UPDATED'",
                Integer.class,
                actor
            )
        );
        assertThrows(UnsupportedOperationException.class, () -> resumed.sources().clear());
    }

    @Test
    void migrationEnforcesSingletonIntervalArrayAndEnabledSourceConstraints() {
        settings.save(false, Duration.ofMinutes(1), actor, List.of(), actor);

        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE job_crawl_settings SET id=2 WHERE id=1")
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE job_crawl_settings SET fixed_delay_millis=59999 WHERE id=1")
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE job_crawl_settings SET fixed_delay_millis=2592000001 WHERE id=1")
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE job_crawl_settings SET sources_json='{}'::jsonb WHERE id=1")
        );
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE job_crawl_settings SET enabled=true WHERE id=1")
        );
        assertFalse(Boolean.TRUE.equals(settings.find().orElseThrow().get("enabled")));
    }

    @Test
    void collectionIntervalStartsAtCompletionInsteadOfTheOldStartTime() {
        String name = "completed-collection";
        UUID owner = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO job_collection_leases(name,lease_token,lease_until,last_started_at,last_completed_at) "
                + "VALUES(?,?,now()-interval '1 minute',now()-interval '8 hours',now()-interval '1 minute')",
            name,
            UUID.randomUUID()
        );

        assertFalse(collection.acquireCollectionLease(name, owner, Duration.ofMinutes(15), Duration.ofHours(6)));
        jdbc.update("UPDATE job_collection_leases SET last_completed_at=now()-interval '7 hours' WHERE name=?", name);
        assertTrue(collection.acquireCollectionLease(name, owner, Duration.ofMinutes(15), Duration.ofHours(6)));
        assertTrue(collection.completeLease(name, owner, null));
        assertFalse(
            collection.acquireCollectionLease(name, UUID.randomUUID(), Duration.ofMinutes(15), Duration.ofHours(6))
        );
    }

    @Test
    void anExpiredCrashLeaseCanRecoverWithoutACompletionTimestamp() {
        String name = "crashed-collection";
        UUID owner = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO job_collection_leases(name,lease_token,lease_until,last_started_at,last_completed_at) "
                + "VALUES(?,?,now()-interval '1 minute',now()-interval '8 hours',NULL)",
            name,
            UUID.randomUUID()
        );

        assertTrue(collection.acquireCollectionLease(name, owner, Duration.ofMinutes(15), Duration.ofHours(6)));
        assertFalse(collection.completeLease(name, UUID.randomUUID(), "Stale worker"));
        assertTrue(collection.completeLease(name, owner, null));
    }

    private CrawlSettingsService service() {
        JobCollectionProperties environment = new JobCollectionProperties();
        environment.setEnabled(true);
        environment.setActorId(actor);
        JobCollectionProperties.Source source = new JobCollectionProperties.Source();
        source.setSourceSite("ITVIEC");
        source.setKeyword("Java");
        source.setRoleCategory("Backend");
        source.setLocation("Ho Chi Minh");
        environment.setSources(List.of(source));
        return new CrawlSettingsService(settings, environment, collection, current);
    }
}
