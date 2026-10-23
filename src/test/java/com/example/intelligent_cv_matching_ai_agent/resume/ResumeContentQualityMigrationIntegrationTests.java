package com.example.intelligent_cv_matching_ai_agent.resume;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ResumeContentQualityMigrationIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    private Connection connection;

    private JdbcTemplate jdbc;

    private String schema;

    private String migration;

    @BeforeEach
    void prepareIsolatedSchema() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        schema = "cv_content_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        connection.setSchema(schema);
        jdbc.execute("CREATE TABLE resume_analyses(id integer PRIMARY KEY,summary text)");
        jdbc.update("INSERT INTO resume_analyses(id,summary) VALUES(1,?)", "Existing analysis");
        try (var input = new ClassPathResource("db/migration/V16__resume_content_quality.sql").getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void addsNullableJsonbWithoutChangingHistoricalAnalyses() {
        jdbc.execute(migration);
        assertEquals("jsonb", column().get("udt_name"));
        assertEquals("YES", column().get("is_nullable"));
        assertEquals("Existing analysis", jdbc.queryForObject("SELECT summary FROM resume_analyses", String.class));
        assertNull(jdbc.queryForObject("SELECT content_quality_json FROM resume_analyses", Object.class));
        jdbc.update(
            "UPDATE resume_analyses SET content_quality_json=?::jsonb WHERE id=1",
            "{\"rubric_version\":\"cv-content-v1\"}"
        );
        assertEquals(
            "cv-content-v1",
            jdbc.queryForObject("SELECT content_quality_json->>'rubric_version' FROM resume_analyses", String.class)
        );
    }

    @Test
    void acceptsAnExistingNullableJsonbColumnAndPreservesItsDataOnRepeatedExecution() {
        jdbc.execute("ALTER TABLE resume_analyses ADD COLUMN content_quality_json jsonb");
        jdbc.update(
            "UPDATE resume_analyses SET content_quality_json=?::jsonb WHERE id=1",
            "{\"rubric_version\":\"cv-content-v1\",\"score\":88}"
        );
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM resume_analyses WHERE id=1");
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertEquals(before, jdbc.queryForMap("SELECT * FROM resume_analyses WHERE id=1"));
        assertEquals("jsonb", column().get("udt_name"));
        assertEquals("YES", column().get("is_nullable"));
    }

    @Test
    void rejectsAnIncompatibleExistingTypeWithoutConvertingOrDiscardingIt() {
        jdbc.execute("ALTER TABLE resume_analyses ADD COLUMN content_quality_json text");
        jdbc.update("UPDATE resume_analyses SET content_quality_json='Existing free text' WHERE id=1");
        DataAccessException error = assertThrows(DataAccessException.class, () -> jdbc.execute(migration));
        assertTrue(error.getMessage().contains("must be nullable JSONB"));
        assertEquals("text", column().get("udt_name"));
        assertEquals(
            "Existing free text",
            jdbc.queryForObject("SELECT content_quality_json FROM resume_analyses WHERE id=1", String.class)
        );
    }

    @Test
    void rejectsAnExistingNotNullConstraintInsteadOfChangingItsSemantics() {
        jdbc.execute("ALTER TABLE resume_analyses ADD COLUMN content_quality_json jsonb NOT NULL DEFAULT '{}'::jsonb");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM resume_analyses WHERE id=1");
        DataAccessException error = assertThrows(DataAccessException.class, () -> jdbc.execute(migration));
        assertTrue(error.getMessage().contains("must be nullable JSONB"));
        assertEquals("NO", column().get("is_nullable"));
        assertEquals(before, jdbc.queryForMap("SELECT * FROM resume_analyses WHERE id=1"));
    }

    private Map<String, Object> column() {
        return jdbc.queryForMap(
            "SELECT udt_name,is_nullable FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name='resume_analyses' AND column_name='content_quality_json'",
            schema
        );
    }
}
