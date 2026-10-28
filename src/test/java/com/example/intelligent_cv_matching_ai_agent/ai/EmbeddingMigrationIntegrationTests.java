package com.example.intelligent_cv_matching_ai_agent.ai;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class EmbeddingMigrationIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @Test
    void upgradesExistingVectorsWithoutTruncationAndRequiresReindexing() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).target("9")
            .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        UUID user = UUID.randomUUID();
        UUID resume = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        String legacy = vector(1536);
        jdbc.update(
            "INSERT INTO users(id,email,full_name,password_hash,status) VALUES(?,?,?,?,'ACTIVE')",
            user,
            "migration@example.test",
            "Migration Test",
            "test-only-unusable-password-hash"
        );
        jdbc.update("INSERT INTO candidate_profiles(user_id) VALUES(?)", user);
        jdbc.update(
            "INSERT INTO resumes(id,user_id,title,source,status) VALUES(?,?,'Migration CV','MANUAL','READY')",
            resume,
            user
        );
        jdbc.update(
            ("INSERT INTO market_job_posts(id,source_site,raw_content_hash,job_title,role_category,raw_content) "
                + "VALUES(?,'MIGRATION_TEST','migration-hash','Migration job','BACKEND','Java')"),
            job
        );
        jdbc.update(
            ("INSERT INTO rag_documents(id,uploaded_by,title,source_type,embedding_status) "
                + "VALUES(?,?,'Migration RAG','TEXT','COMPLETED')"),
            document,
            user
        );
        jdbc.update("INSERT INTO rag_chunks(id,document_id,chunk_index,content) VALUES(?,?,0,'Java')", chunk, document);
        List<String> tables =
            List.of("career_profile_embeddings", "resume_embeddings", "market_job_embeddings", "rag_chunk_embeddings");
        List<String> owners = List.of("user_id", "resume_id", "market_job_id", "chunk_id");
        List<UUID> ids = List.of(user, resume, job, chunk);
        for (int index = 0; index < tables.size(); index++) {
            jdbc.update(
                "INSERT INTO " + tables.get(index) + "(" + owners.get(index)
                    + ",provider,model,embedding) VALUES(?,'openai','legacy-model',?::vector)",
                ids.get(index),
                legacy
            );
        }

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();

        for (String table : tables) {
            assertEquals(legacy, jdbc.queryForObject("SELECT embedding_legacy_1536::text FROM " + table, String.class));
            assertNull(jdbc.queryForObject("SELECT embedding::text FROM " + table, String.class));
            if (!table.equals("rag_chunk_embeddings")) {
                assertEquals(false, jdbc.queryForObject("SELECT is_current FROM " + table, Boolean.class));
            }
        }
        assertEquals(
            "PENDING",
            jdbc.queryForObject("SELECT embedding_status FROM rag_documents WHERE id=?", String.class, document)
        );
        assertEquals(
            4,
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef LIKE '%USING hnsw%'",
                Integer.class
            )
        );
        String current = vector(1024);
        jdbc.update(
            "INSERT INTO rag_chunk_embeddings(chunk_id,provider,model,embedding) VALUES(?,'ollama',?,?::vector)",
            chunk,
            "qwen3-embedding:0.6b",
            current
        );
        assertEquals(
            1024,
            jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM rag_chunk_embeddings WHERE provider='ollama'",
                Integer.class
            )
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE rag_chunk_embeddings SET embedding=?::vector WHERE provider='ollama'", legacy)
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE rag_chunk_embeddings SET embedding=NULL WHERE provider='ollama'")
        );
        assertEquals(
            1.0,
            jdbc.queryForObject(
                "SELECT 1-(embedding <=> ?::vector) FROM rag_chunk_embeddings WHERE provider='ollama'",
                Double.class,
                current
            ),
            1e-7
        );
    }

    private static String vector(
        int dimensions) {
        return "[1," + String.join(",", Collections.nCopies(dimensions - 1, "0")) + "]";
    }
}
