package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses only an isolated schema in an explicit test database or a disposable PostgreSQL container. */
class LearningRepositoryIntegrationTest {

    private static PostgreSQLContainer container;

    private static DriverManagerDataSource database;

    private DriverManagerDataSource scopedDatabase;

    private JdbcTemplate jdbc;

    private LearningRepository repository;

    private String schema;

    private UUID admin;

    @BeforeAll
    static void connectToDisposablePostgres() {
        String url = System.getenv("EDUNEXUS_TEST_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            database = new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_USER", "edunexus_test"),
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_PASSWORD", "")
            );
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "A disposable PostgreSQL database is required");
        container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        database =
            new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @AfterAll
    static void stopDisposablePostgres() {
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void createIsolatedSchemaAndApplyCourseMigration() throws Exception {
        schema = "learning_courses_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);
        scopedDatabase = new DriverManagerDataSource(database.getUrl(), database.getUsername(), database.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        scopedDatabase.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(scopedDatabase);
        jdbc.setQueryTimeout(5);
        jdbc.execute("CREATE TABLE users(id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE career_assistant_message_sources(id uuid PRIMARY KEY)");
        jdbc.execute("CREATE TABLE rag_documents(id uuid PRIMARY KEY,metadata jsonb,embedding_status text)");
        jdbc.execute("""
            CREATE TABLE learning_resources(
                id uuid PRIMARY KEY, title text NOT NULL, resource_type text, provider text, url text UNIQUE,
                description text, is_free boolean NOT NULL DEFAULT true, source_type text NOT NULL DEFAULT 'WEB',
                metadata_json jsonb, is_active boolean NOT NULL DEFAULT true
            )
            """);
        jdbc.execute("CREATE TABLE roadmaps(id uuid PRIMARY KEY)");
        jdbc.execute("""
            CREATE TABLE roadmap_node_resources(
                roadmap_node_id uuid NOT NULL, resource_id uuid NOT NULL REFERENCES learning_resources(id),
                sequence_order smallint, recommendation_reason text, PRIMARY KEY(roadmap_node_id,resource_id)
            )
            """);
        try (var connection = scopedDatabase.getConnection()) {
            ScriptUtils
                .executeSqlScript(connection, new ClassPathResource("db/migration/V8__curated_course_links.sql"));
        }
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES(?)", admin);
        repository = new LearningRepository(new SqlStore(jdbc));
    }

    @AfterEach
    void dropOnlyTheRandomFixtureSchema() {
        if (schema != null) {
            new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void onlyApprovedActiveCoursesWithEnabledDocumentsAreRecommendedAndOfficialDocsRemainAvailable() {
        UUID approved = course("APPROVED", true, false, "java");
        course("PENDING_REVIEW", true, false, "java");
        course("ARCHIVED", true, false, "java");
        course("APPROVED", false, false, "java");
        course("APPROVED", true, true, "java");
        UUID official = resource("Official Java documentation", "REFERENCE", "java");

        List<Map<String, Object>> result = repository.listLearningResourcesForSkill("java", "%Java%");

        assertEquals(List.of(approved, official), result.stream().map(row -> row.get("id")).toList());
        assertEquals(
            List.of(approved),
            repository.listApprovedCourseResources(List.of(), "java-backend-developer").stream()
                .map(row -> row.get("id")).toList()
        );
        assertTrue(repository.listApprovedCourseResources(List.of(), "javascript-developer").isEmpty());
        assertEquals(
            2,
            repository
                .listLearningResourcesByUrls(jdbc.queryForList("SELECT url FROM learning_resources", String.class))
                .size()
        );
    }

    @Test
    void arbitrarySkillsMatchAndApprovedCoursesComeBeforeCatalogLimits() {
        UUID course = course("APPROVED", true, false, "apache-flink");
        for (int index = 0; index < 8; index++) {
            resource("A official Flink resource " + index, "REFERENCE", "apache-flink");
        }

        List<Map<String, Object>> result = repository.listLearningResourcesForSkill("apache-flink", "%Flink%");

        assertEquals(5, result.size());
        assertEquals(course, result.getFirst().get("id"));
        assertEquals(
            course,
            repository.listApprovedCourseResources(List.of("apache-flink"), "data-engineer").getFirst().get("id")
        );
    }

    @Test
    void nodeSnapshotsAndMigrationBackfillSurviveChangedUrlsAndArchivedCourses() throws Exception {
        UUID resource = course("APPROVED", true, false, "java");
        UUID oldNode = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO roadmap_node_resources(roadmap_node_id,resource_id,sequence_order) VALUES(?,?,1)",
            oldNode,
            resource
        );
        try (var connection = scopedDatabase.getConnection()) {
            ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V9__learning_recommendation_snapshots.sql")
            );
        }
        UUID newNode = UUID.randomUUID();
        Map<String, Object> selected = repository.listLearningResourcesForSkill("java", "%Java%").getFirst();
        String originalUrl = selected.get("url").toString();
        repository.updateRoadmapNodeResources(newNode, resource, 1, "Relevant Java course", selected);
        Map<String, Object> before = repository.listRoadmapNodeResourcesByRoadmapNodeId(newNode).getFirst();
        jdbc.update(
            "UPDATE learning_resources SET url=?,title='Changed title',is_active=false WHERE id=?",
            "https://www.coursera.org/learn/changed",
            resource
        );
        jdbc.update("UPDATE rag_course_links SET status='ARCHIVED' WHERE learning_resource_id=?", resource);
        jdbc.update("UPDATE rag_documents SET metadata='{\"disabled\":true}'::jsonb");

        Map<String, Object> after = repository.listRoadmapNodeResourcesByRoadmapNodeId(newNode).getFirst();

        assertEquals(before, after);
        assertEquals(resource, after.get("id"));
        assertEquals(originalUrl, after.get("url"));
        assertEquals("Relevant Java course", after.get("recommendation_reason"));
        assertEquals(originalUrl, repository.listRoadmapNodeResourcesByRoadmapNodeId(oldNode).getFirst().get("url"));
        assertTrue(repository.listLearningResourcesForSkill("java", "%Java%").isEmpty());
    }

    private UUID course(
        String status,
        boolean active,
        boolean disabled,
        String skill) {
        UUID resource = resource("Z approved course", "ADMIN_CURATED", skill);
        UUID document = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO rag_documents(id,metadata,embedding_status) VALUES(?,?::jsonb,'PENDING')",
            document,
            "{\"disabled\":" + disabled + "}"
        );
        jdbc.update("UPDATE learning_resources SET is_active=? WHERE id=?", active, resource);
        jdbc.update(
            "INSERT INTO rag_course_links(url,provider,title,description,skills,language,is_free,status,"
                + "learning_resource_id,rag_document_id,created_by_user_id,reviewed_by_user_id,reviewed_at) "
                + "SELECT url,'Coursera',title,'Reviewed description',?::jsonb,'en',true,?,?,?, ?,?,now() "
                + "FROM learning_resources WHERE id=?",
            "[\"" + skill + "\"]",
            status,
            resource,
            document,
            admin,
            admin,
            resource
        );
        return resource;
    }

    private UUID resource(
        String title,
        String source,
        String skill) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO learning_resources(id,title,resource_type,provider,url,source_type,metadata_json) "
                + "VALUES(?,?,'COURSE','Coursera',?,?,?::jsonb)",
            id,
            title,
            "https://www.coursera.org/learn/" + id,
            source,
            "{\"skill_slugs\":[\"" + skill + "\"]}"
        );
        return id;
    }
}
