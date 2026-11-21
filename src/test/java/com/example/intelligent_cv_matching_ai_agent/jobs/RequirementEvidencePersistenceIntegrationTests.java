package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.RequirementEvidenceRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
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
class RequirementEvidencePersistenceIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @Test
    void nativeVectorsAndReportsArePrivateModelSpecificAndDeletedWithTheOwner() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();
        var jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        var repository = new RequirementEvidenceRepository(new SqlStore(jdbc));
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        for (UUID id : List.of(owner, other))
            jdbc.update(
                ("INSERT INTO users(id,email,full_name,status,password_hash) VALUES(?,?,"
                    + "?,'ACTIVE','test-only-unusable-hash')"),
                id,
                id + "@evidence.test",
                "Evidence test"
            );
        float[] vector = new float[1024];
        vector[0] = 1;
        String hash = "a".repeat(64);
        repository.saveVector(owner, "gemini", "model-a", hash, vector);
        repository.saveVector(owner, "gemini", "model-a", hash, vector);
        assertArrayEquals(vector, repository.vector(owner, "gemini", "model-a", hash).orElseThrow());
        assertTrue(repository.vector(other, "gemini", "model-a", hash).isEmpty());
        assertTrue(repository.vector(owner, "gemini", "model-b", hash).isEmpty());
        repository.saveReport(owner, hash, Map.of("score", 0.5, "version", "requirement-evidence-v1"));
        assertEquals(0.5, repository.report(owner, hash).orElseThrow().get("score"));
        assertTrue(repository.report(other, hash).isEmpty());
        assertThrows(
            DataAccessException.class,
            () -> repository.saveVector(owner, "gemini", "model-b", hash, new float[10])
        );
        jdbc.update(
            ("UPDATE matching_evidence_reports SET created_at=now()-interval '25 " + "hours' WHERE user_id=?"),
            owner
        );
        assertTrue(repository.report(owner, hash).isEmpty());
        jdbc.update("DELETE FROM users WHERE id=?", owner);
        assertTrue(repository.vector(owner, "gemini", "model-a", hash).isEmpty());
    }
}
