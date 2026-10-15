package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL queries in a dedicated schema; no application data or external services are used. */
@EnabledIfEnvironmentVariable(named = "EDUNEXUS_LOCATION_TEST_DATABASE_URL", matches = ".+")
class JobSearchRepositoryIntegrationTest {

    private JdbcTemplate administrator;

    private JdbcTemplate jdbc;

    private String schema;

    private JobService jobs;

    @BeforeEach
    void prepareIsolatedSchema() {
        String url = System.getenv("EDUNEXUS_LOCATION_TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_USER", "postgres");
        String password = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_PASSWORD", "");
        administrator = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        schema = "location_test_" + UUID.randomUUID().toString().replace("-", "");
        administrator.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(
            new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                username,
                password
            )
        );
        jdbc.execute("""
            CREATE TABLE market_job_posts (
                id uuid PRIMARY KEY, job_title text, company_name text, location text,
                salary_text text, role_category text, posted_at timestamptz, valid_through timestamptz,
                employment_type text, source_url text, source_site text, status text,
                collected_at timestamptz DEFAULT now()
            )
            """);
        jdbc.execute("""
            CREATE TABLE market_job_sources (
                id uuid PRIMARY KEY, market_job_id uuid, source_url text, source_site text,
                status text, valid_through timestamptz, last_seen_at timestamptz
            )
            """);
        jobs = new JobService(new JobRepository(new SqlStore(jdbc)), mock(CurrentUser.class), mock(AiService.class));
    }

    @AfterEach
    void removeOnlyOwnedSchema() {
        if (administrator != null && schema != null && schema.matches("location_test_[0-9a-f]{32}")) {
            administrator.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void aliasesAccentsAndDecomposedUnicodeMatchSelectedCities() {
        UUID hanoi = insert("Ha Noi");
        UUID danang = insert("Đà Nẵng");
        UUID cantho = insert("Cần Thơ");
        UUID dalat = insert(Normalizer.normalize("ĐÀ LẠT", Normalizer.Form.NFD));
        UUID singapore = insert("Singapore");

        assertEquals(List.of(hanoi), ids(jobs.jobs("Java", "Hà Nội", 20, 0)));
        assertEquals(List.of(hanoi), ids(jobs.jobs("Java", "Hanoi", 20, 0)));
        assertEquals(List.of(danang), ids(jobs.jobs("Java", "DaNang", 20, 0)));
        assertEquals(List.of(cantho), ids(jobs.jobs("Java", "CanTho", 20, 0)));
        assertEquals(List.of(dalat), ids(jobs.jobs("Java", "Da Lat", 20, 0)));
        assertEquals(List.of(singapore), ids(jobs.jobs("Java", "Singapore", 20, 0)));
    }

    @Test
    void absentLocationIncludesEveryRegionAndMissingGeography() {
        insert("Ho Chi Minh");
        insert("Ha Noi");
        insert("Da Nang");
        insert("Remote");
        insert(null);

        for (String location : List.of("", " ", "Toàn quốc", "All Cities")) {
            assertEquals(5, jobs.jobs("Java", location, 20, 0).size());
        }
        assertEquals(5, jobs.jobs("Java", null, 20, 0).size());
        assertEquals(1, jobs.jobs("Java", "HCM", 20, 0).size());
        assertEquals(1, jobs.jobs("Java", "Telecommute", 20, 0).size());
    }

    @Test
    void userLocationWildcardsAreTreatedAsLiteralCharacters() {
        UUID literal = insert("Cần Thơ_100%!");
        insert("Can ThoA100whatever");
        insert("Ha Noi");

        assertEquals(List.of(literal), ids(jobs.jobs("Java", "Cần Thơ_100%!", 20, 0)));
        assertEquals(List.of(literal), ids(jobs.jobs("Java", "%", 20, 0)));
        assertEquals(List.of(literal), ids(jobs.jobs("Java", "_", 20, 0)));
    }

    @Test
    void locationSearchPreservesActiveStatusExpiryAndPagination() {
        UUID active = insert("Đà Lạt");
        UUID removed = insert("Đà Lạt");
        UUID expired = insert("Đà Lạt");
        jdbc.update("UPDATE market_job_posts SET status='REMOVED' WHERE id=?", removed);
        jdbc.update("UPDATE market_job_posts SET valid_through=now()-interval '1 minute' WHERE id=?", expired);

        assertEquals(List.of(active), ids(jobs.jobs("Java", "Da Lat", 20, 0)));
        assertTrue(jobs.jobs("Java", "Da Lat", 20, 1).isEmpty());
    }

    private UUID insert(
        String location) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO market_job_posts(id,job_title,location,status,source_site,source_url) "
                + "VALUES(?,?,?,'ACTIVE',?,?)",
            id,
            "Java Developer",
            location,
            "ITVIEC",
            "https://itviec.com/it-jobs/location-" + id + "-1234"
        );
        return id;
    }

    private static List<UUID> ids(
        List<Map<String, Object>> jobs) {
        return jobs.stream().map(job -> (UUID) job.get("id")).toList();
    }
}
