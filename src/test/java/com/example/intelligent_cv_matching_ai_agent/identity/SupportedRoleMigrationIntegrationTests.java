package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAnalysisRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real upgrade from V33 in a disposable database, never the application's database. */
@Testcontainers(disabledWithoutDocker = true)
class SupportedRoleMigrationIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    private static JdbcTemplate jdbc;

    private static SqlStore sql;

    private static TokenService tokens;

    private static final UUID retired = UUID.randomUUID();

    private static final UUID retiredManager = UUID.randomUUID();

    private static final UUID deleted = UUID.randomUUID();

    private static final UUID manager = UUID.randomUUID();

    private static final UUID admin = UUID.randomUUID();

    private static final UUID job = UUID.randomUUID();

    private static final UUID announcement = UUID.randomUUID();

    private static String retiredAccess;

    private static String managerAccess;

    @BeforeAll
    static void upgradePopulatedLegacySchema() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("33").load().migrate();
        jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        sql = new SqlStore(jdbc);
        tokens = new TokenService(new TokenRepository(sql), "test-only-supported-roles-secret-0123456789", 600, 3600);
        for (UUID id : List.of(retired, retiredManager, deleted)) {
            person(id, "MANAGEMENT");
            jdbc.update(
                "INSERT INTO management_profiles(user_id,sub_role) VALUES(?,?)",
                id,
                id.equals(retiredManager) ? "MANAGER" : "STAFF"
            );
        }
        person(manager, "MANAGER");
        person(admin, "ADMIN");
        retiredAccess = (String) tokens.issue(retired).get("accessToken");
        managerAccess = (String) tokens.issue(manager).get("accessToken");
        jdbc.update("UPDATE users SET status='DELETED',deleted_at=now() WHERE id=?", deleted);
        UUID task = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO management_tasks(id,title,task_type,assigned_by_user_id,assigned_to_user_id) "
                + "VALUES(?,'Legacy test work','GENERAL',?,?)",
            task,
            retiredManager,
            retired
        );
        jdbc.update(
            "INSERT INTO market_job_posts(id,source_site,raw_content_hash,job_title,role_category,raw_content,"
                + "created_by_user_id,task_id,status) VALUES(?,'test',?,'Engineer','IT','Synthetic JD',?,?,'PENDING')",
            job,
            UUID.randomUUID().toString(),
            retired,
            task
        );
        jdbc.update(
            "INSERT INTO market_crawl_runs(source_site,keyword,role_category,requested_limit,status,task_id) "
                + "VALUES('test','Engineer','IT',1,'COMPLETED',?)",
            task
        );
        jdbc.update(
            "INSERT INTO announcements(id,created_by_user_id,title,content,status,publish_at) "
                + "VALUES(?,?,'Internal notice','Private historical text','PUBLISHED',now())",
            announcement,
            admin
        );
        jdbc.update(
            "INSERT INTO announcement_targets(announcement_id,target_type,target_role) VALUES(?,'ROLE','MANAGEMENT')",
            announcement
        );
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();
    }

    @Test
    void retiredAccountsArePreservedSuspendedAndNeverPromotedWhileCurrentManagerSessionSurvives() {
        for (UUID id : List.of(retired, retiredManager)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT role,status FROM users WHERE id=?", id);
            assertEquals("CANDIDATE", row.get("role"));
            assertEquals("SUSPENDED", row.get("status"));
            assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM candidate_profiles WHERE user_id=?", Integer.class, id)
            );
        }
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, deleted));
        assertEquals(401, assertThrows(ApiException.class, () -> tokens.authenticate(retiredAccess)).status());
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NULL",
                Integer.class,
                retired
            )
        );
        assertEquals("MANAGER", tokens.authenticate(managerAccess).get("role"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, manager));
    }

    @Test
    void obsoleteTablesAndLinksAreRemovedWithoutLosingJobAndCrawlHistory() {
        assertNull(jdbc.queryForObject("SELECT to_regclass('management_tasks')", String.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('management_profiles')", String.class));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
                    + "AND table_name IN ('market_job_posts','market_crawl_runs') AND column_name='task_id'",
                Integer.class
            )
        );
        assertEquals(
            retired,
            jdbc.queryForObject("SELECT created_by_user_id FROM market_job_posts WHERE id=?", UUID.class, job)
        );
        assertEquals(
            "PENDING",
            jdbc.queryForObject("SELECT status FROM market_job_posts WHERE id=?", String.class, job)
        );
        assertFalse(new ManagerOperationsRepository(sql).crawlReports("test", null, 20, 0).isEmpty());
    }

    @Test
    void onlySupportedRolesCanBeStoredAndRetiredAnnouncementsDoNotGainANewAudience() {
        for (String role : List.of("STAFF", "MANAGEMENT")) {
            assertThrows(DataAccessException.class, () -> person(UUID.randomUUID(), role));
            assertThrows(
                DataAccessException.class,
                () -> jdbc.update(
                    "INSERT INTO announcement_targets(announcement_id,target_type,target_role) VALUES(?,'ROLE',?)",
                    announcement,
                    role
                )
            );
        }
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM announcements WHERE id=?", Integer.class, announcement)
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM announcement_targets WHERE announcement_id=?",
                Integer.class,
                announcement
            )
        );
        for (String role : List.of("CANDIDATE", "CAREER_CONSULTANT", "MANAGER", "ADMIN")) {
            assertDoesNotThrow(() -> person(UUID.randomUUID(), role));
        }
    }

    @Test
    void candidateReportsRemainPrivateToParticipantsAndAdmin() {
        UUID candidate = UUID.randomUUID();
        UUID consultant = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID thread = UUID.randomUUID();
        person(candidate, "CANDIDATE");
        person(consultant, "CAREER_CONSULTANT");
        person(outsider, "CANDIDATE");
        jdbc.update(
            "INSERT INTO consultation_threads(id,candidate_user_id,consultant_user_id,subject) "
                + "VALUES(?,?,?,'Private report test')",
            thread,
            candidate,
            consultant
        );
        var repository = new ConsultationAnalysisRepository(sql);
        assertEquals(candidate, repository.candidateIdForThread(thread, candidate));
        assertEquals(candidate, repository.candidateIdForThread(thread, consultant));
        assertEquals(candidate, repository.candidateIdForThread(thread, admin));
        for (UUID denied : List.of(manager, outsider, retired)) {
            assertEquals(
                404,
                assertThrows(ApiException.class, () -> repository.candidateIdForThread(thread, denied)).status()
            );
        }
    }

    private static void person(
        UUID id,
        String role) {
        jdbc.update(
            "INSERT INTO users(id,email,full_name,password_hash,role,status) "
                + "VALUES(?,?,'Migration test','test-only-unusable-hash',?,'ACTIVE')",
            id,
            id + "@example.test",
            role
        );
    }
}
