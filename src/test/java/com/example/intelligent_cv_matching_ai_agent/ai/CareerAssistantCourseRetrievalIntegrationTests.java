package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfEnvironmentVariable(named = "EDUNEXUS_LOCATION_TEST_DATABASE_URL", matches = ".+")
class CareerAssistantCourseRetrievalIntegrationTests {

    private JdbcTemplate administrator;

    private JdbcTemplate jdbc;

    private CareerAssistantRepository repository;

    private String schema;

    private final UUID admin = UUID.randomUUID();

    private final UUID candidate = UUID.randomUUID();

    private final UUID otherCandidate = UUID.randomUUID();

    @BeforeEach
    void prepareIsolatedRetrievalSchema() {
        String url = System.getenv("EDUNEXUS_LOCATION_TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_USER", "postgres");
        String password = System.getenv().getOrDefault("EDUNEXUS_LOCATION_TEST_DATABASE_PASSWORD", "");
        administrator = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        assumeTrue(
            Boolean.TRUE
                .equals(administrator.queryForObject("SELECT to_regtype('public.vector') IS NOT NULL", Boolean.class)),
            "This retrieval test requires the application's pgvector extension in the test database"
        );
        schema = "career_course_test_" + UUID.randomUUID().toString().replace("-", "");
        administrator.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(
            new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public",
                username,
                password
            )
        );
        jdbc.execute("CREATE TABLE users (id uuid PRIMARY KEY,role text)");
        jdbc.execute("""
            CREATE TABLE rag_documents (
                id uuid PRIMARY KEY,uploaded_by uuid,title text,metadata jsonb,embedding_status text
            )
            """);
        jdbc.execute("CREATE TABLE rag_chunks (id uuid PRIMARY KEY,document_id uuid,content text)");
        jdbc.execute("""
            CREATE TABLE rag_chunk_embeddings (
                chunk_id uuid,provider text,model text,embedding vector(2)
            )
            """);
        jdbc.execute("CREATE TABLE learning_resources (id uuid PRIMARY KEY,is_active boolean)");
        jdbc.execute("""
            CREATE TABLE rag_course_links (
                id uuid PRIMARY KEY,rag_document_id uuid UNIQUE,learning_resource_id uuid,
                status text,url text,provider text
            )
            """);
        jdbc.update(
            "INSERT INTO users(id,role) VALUES(?,'ADMIN'),(?,'CANDIDATE'),(?,'CANDIDATE')",
            admin,
            candidate,
            otherCandidate
        );
        repository = new CareerAssistantRepository(new SqlStore(jdbc));
    }

    @AfterEach
    void removeOnlyTheFixtureSchema() {
        if (administrator != null && schema != null && schema.matches("career_course_test_[0-9a-f]{32}")) {
            administrator.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void retrievalIncludesOnlyApprovedActiveCoursesAndPreservesExistingRagRestrictions() {
        UUID approved = document(admin);
        course(approved, "APPROVED", true);
        course(document(admin), "ARCHIVED", true);
        course(document(admin), "PENDING_REVIEW", true);
        course(document(admin), "APPROVED", false);
        UUID disabled = document(admin);
        course(disabled, "APPROVED", true);
        jdbc.update("UPDATE rag_documents SET metadata='{\"disabled\":true}'::jsonb WHERE id=?", disabled);

        UUID adminDocument = document(admin);
        UUID ownDocument = document(candidate);
        jdbc.update(
            "UPDATE rag_documents SET metadata='{\"source_url\":\"javascript:alert(1)\",\"provider\":\"Fake\"}'::jsonb "
                + "WHERE id=?",
            ownDocument
        );
        document(otherCandidate);
        UUID pending = document(admin);
        jdbc.update("UPDATE rag_documents SET embedding_status='PENDING' WHERE id=?", pending);
        UUID otherProvider = document(admin);
        jdbc.update(
            "UPDATE rag_chunk_embeddings SET provider='OTHER' WHERE chunk_id IN "
                + "(SELECT id FROM rag_chunks WHERE document_id=?)",
            otherProvider
        );
        UUID otherModel = document(admin);
        jdbc.update(
            "UPDATE rag_chunk_embeddings SET model='OTHER' WHERE chunk_id IN "
                + "(SELECT id FROM rag_chunks WHERE document_id=?)",
            otherModel
        );
        UUID irrelevant = document(admin);
        jdbc.update(
            "UPDATE rag_chunk_embeddings SET embedding='[0,1]'::vector WHERE chunk_id IN "
                + "(SELECT id FROM rag_chunks WHERE document_id=?)",
            irrelevant
        );

        List<Map<String, Object>> retrieved = retrieve();

        assertEquals(Set.of(approved, adminDocument, ownDocument), documentIds(retrieved));
        Map<String, Object> course =
            retrieved.stream().filter(row -> approved.equals(row.get("document_id"))).findFirst().orElseThrow();
        assertEquals("https://www.coursera.org/learn/java-programming", course.get("source_url"));
        assertEquals("Coursera", course.get("source_provider"));
        for (Map<String, Object> source : retrieved) {
            if (!approved.equals(source.get("document_id"))) {
                assertNull(source.get("source_url"));
                assertNull(source.get("source_provider"));
            }
        }

        jdbc.update("UPDATE rag_course_links SET status='ARCHIVED' WHERE rag_document_id=?", approved);
        assertEquals(Set.of(adminDocument, ownDocument), documentIds(retrieve()));
    }

    private UUID document(
        UUID uploader) {
        UUID document = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO rag_documents(id,uploaded_by,title,metadata,embedding_status) "
                + "VALUES(?,?,'Java course','{}'::jsonb,'COMPLETED')",
            document,
            uploader
        );
        jdbc.update("INSERT INTO rag_chunks(id,document_id,content) VALUES(?,?,'Java fundamentals')", chunk, document);
        jdbc.update(
            "INSERT INTO rag_chunk_embeddings(chunk_id,provider,model,embedding) "
                + "VALUES(?,'OPENAI','test-embedding','[1,0]'::vector)",
            chunk
        );
        return document;
    }

    private void course(
        UUID document,
        String status,
        boolean active) {
        UUID resource = UUID.randomUUID();
        jdbc.update("INSERT INTO learning_resources(id,is_active) VALUES(?,?)", resource, active);
        jdbc.update(
            "INSERT INTO rag_course_links(id,rag_document_id,learning_resource_id,status,url,provider) "
                + "VALUES(?,?,?,?,'https://www.coursera.org/learn/java-programming','Coursera')",
            UUID.randomUUID(),
            document,
            resource,
            status
        );
    }

    private List<Map<String, Object>> retrieve() {
        return repository.listRagChunksByProvider("[1,0]", "OPENAI", "test-embedding", candidate, "[1,0]", "[1,0]");
    }

    private Set<Object> documentIds(
        List<Map<String, Object>> sources) {
        return sources.stream().map(row -> row.get("document_id")).collect(Collectors.toSet());
    }
}
