package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies confirmation against the real section-column definitions in an isolated PostgreSQL schema. */
class ResumeConfirmationPersistenceIntegrationTests {

    private static PostgreSQLContainer container;

    private static DriverManagerDataSource database;

    private final UUID actor = UUID.randomUUID();

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final AiService ai = mock(AiService.class);

    private String schema;

    private JdbcTemplate jdbc;

    private ResumeService service;

    private TransactionTemplate transactions;

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
    void createFixtureUsingMigrationColumns() throws Exception {
        schema = "resume_confirm_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource scoped =
            new DriverManagerDataSource(database.getUrl(), database.getUsername(), database.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        scoped.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(scoped);
        jdbc.setQueryTimeout(5);
        String migration;
        try (var input = new ClassPathResource("db/migration/V1__edunexus_complete_schema.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        for (String table : List.of(
            "users",
            "candidate_profiles",
            "resumes",
            "resume_parse_results",
            "resume_experiences",
            "resume_educations",
            "resume_projects",
            "resume_certifications",
            "resume_skills",
            "skills"
        )) {
            Matcher create =
                Pattern.compile("(?s)CREATE TABLE " + Pattern.quote(table) + " \\(.*?\n\\);").matcher(migration);
            assertTrue(create.find(), "Migration must define " + table);
            jdbc.execute(create.group());
        }
        // Only invalidation is exercised; this test does not emulate pgvector inference.
        jdbc.execute("CREATE TABLE gap_analyses(id uuid,resume_id uuid)");
        jdbc.execute("CREATE TABLE roadmaps(gap_analysis_id uuid,is_outdated boolean,updated_at timestamptz)");
        jdbc.update(
            "INSERT INTO users(id,email,full_name,status) VALUES(?,?,?,'ACTIVE')",
            actor,
            actor + "@example.test",
            "Candidate fixture"
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor.toString(),
                "unused",
                List.of(new SimpleGrantedAuthority("ROLE_CANDIDATE"))
            )
        );
        service = new ResumeService(
            new ResumeRepository(new SqlStore(jdbc)),
            new CurrentUser(),
            ai,
            mock(ResumeFileExtractor.class),
            mapper,
            "./target/resume-confirm-fixture"
        );
        transactions = new TransactionTemplate(new DataSourceTransactionManager(scoped));
    }

    @AfterEach
    void cleanUpOnlyTheFixtureSchema() {
        SecurityContextHolder.clearContext();
        if (schema != null)
            new JdbcTemplate(database).execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void manualCreateEditAndConfirmPersistRichEditorDataWithoutUnknownColumnsOrInvalidUuids() {
        transactions.executeWithoutResult(status -> {
            Map<String, Object> created = service.create(
                Map.of("title", "Original CV", "language", "vi", "structured_data", Map.of("summary", "Initial draft")),
                false
            );
            UUID id = (UUID) created.get("id");
            assertEquals("DRAFT", created.get("status"));
            Map<String, Object> changed = service
                .update(id, Map.of("title", "Reviewed CV", "language", "en", "structured_data", richEditorDocument()));
            assertEquals("REVIEW_REQUIRED", changed.get("status"));
            Map<String, Object> confirmed = service.confirm(id, Map.of("is_primary", true));
            assertEquals(id, confirmed.get("id"));
            assertEquals("READY", confirmed.get("status"));
            assertEquals(true, confirmed.get("is_primary"));
            assertEquals("en", confirmed.get("language"));
            assertStoredPresentationAndGeneratedRowIds(confirmed, id);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resumes", Integer.class));
            verify(ai, never()).embed(anyString());
        });
    }

    @Test
    void aiBuildAndRepeatedConfirmationKeepOneOwnedCvAndPreservePresentation() {
        when(ai.generate(eq("CV_BUILD"), anyMap())).thenReturn(richEditorDocument());
        transactions.executeWithoutResult(status -> {
            Map<String, Object> built = service.create(Map.of("title", "AI CV", "language", "vi"), true);
            UUID id = (UUID) built.get("id");
            assertEquals("REVIEW_REQUIRED", built.get("status"));
            Map<String, Object> confirmed = service.confirm(id, Map.of());
            assertEquals("READY", confirmed.get("status"));
            assertStoredPresentationAndGeneratedRowIds(confirmed, id);
            assertEquals(id, service.confirm(id, Map.of()).get("id"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resumes", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resume_experiences", Integer.class));
            verify(ai, never()).embed(anyString());
        });
    }

    private void assertStoredPresentationAndGeneratedRowIds(
        Map<String, Object> confirmed,
        UUID id) {
        Map<?, ?> document = (Map<?, ?>) confirmed.get("structured_data");
        assertEquals(richEditorDocument().get("experience"), document.get("experience"));
        assertEquals(richEditorDocument().get("education"), document.get("education"));
        assertEquals(richEditorDocument().get("certifications"), document.get("certifications"));
        for (String table : List
            .of("resume_experiences", "resume_educations", "resume_projects", "resume_certifications")) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE resume_id=?", id);
            assertInstanceOf(UUID.class, row.get("id"));
            assertEquals(id, row.get("resume_id"));
        }
        assertEquals(
            "Example",
            jdbc.queryForObject("SELECT company_name FROM resume_experiences WHERE resume_id=?", String.class, id)
        );
        assertEquals("3.8/4.0", ((Map<?, ?>) ((List<?>) document.get("education")).getFirst()).get("gpa"));
    }

    private static Map<String, Object> richEditorDocument() {
        return Map.of(
            "summary",
            "Reviewed candidate experience",
            "experience",
            List.of(
                Map.of(
                    "id",
                    "experience-1",
                    "company_name",
                    "Example",
                    "job_title",
                    "Developer",
                    "location",
                    "Hanoi",
                    "start_date",
                    "2024-01-01",
                    "display_start_date",
                    "01/2024",
                    "highlights",
                    List.of("Built APIs")
                )
            ),
            "education",
            List.of(
                Map.of(
                    "id",
                    "education-1",
                    "school_name",
                    "University",
                    "gpa",
                    "3.8/4.0",
                    "display_start_date",
                    "2020",
                    "highlights",
                    List.of("Graduated")
                )
            ),
            "projects",
            List.of(Map.of("id", "project-1", "project_name", "Portfolio", "technologies", List.of("Java"))),
            "certifications",
            List.of(
                Map.of(
                    "id",
                    "certification-1",
                    "name",
                    "Certificate",
                    "issue_date",
                    "2025-01-01",
                    "display_date",
                    "2025"
                )
            )
        );
    }
}
