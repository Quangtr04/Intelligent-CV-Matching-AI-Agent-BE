package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobSkillProficiencyRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class JobSkillProficiencyIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @Test
    void claimsAnalyzesAllSkillsTogetherAndSkipsAnUnchangedJob() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();
        var jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        var db = new JobSkillProficiencyRepository(new SqlStore(jdbc));
        UUID actor = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users(id,email,full_name,password_hash,role,status) VALUES(?,?,?,?,?,?)",
            actor,
            "rubric@example.test",
            "QA Rubric",
            "test-only-unusable-hash",
            "ADMIN",
            "ACTIVE"
        );
        String evidence = "Independently implement Java and Redux features";
        jdbc.update(
            "INSERT INTO market_job_posts(id,source_site,raw_content_hash,job_title,role_category,raw_content,"
                + "parsed_requirements,created_by_user_id) VALUES(?,?,?,?,?,?,?,?)",
            job,
            "TEST",
            "rubric-test-hash",
            "Fullstack Developer",
            "BACKEND",
            evidence,
            evidence,
            actor
        );
        AiService ai = mock(AiService.class);
        when(ai.enabled()).thenReturn(true);
        when(ai.generateForUser(eq(actor), eq("JD_SKILL_PROFICIENCY"), anyMap()))
            .thenReturn(fields("results", List.of(skill("java", evidence), skill("redux", evidence))));
        var service = new JobSkillProficiencyService(db, ai, true, "");
        service.processNext();
        service.processNext();
        verify(ai, times(1)).generateForUser(eq(actor), eq("JD_SKILL_PROFICIENCY"), anyMap());
        assertEquals(
            "COMPLETED",
            jdbc.queryForObject("SELECT skill_proficiency_status FROM market_job_posts WHERE id=?", String.class, job)
        );
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT jsonb_array_length(skill_proficiency_analysis->'results') FROM market_job_posts WHERE id=?",
                Integer.class,
                job
            )
        );
        assertEquals(1, db.statusJobs("COMPLETED", 20, 0).getFirst().get("attempts"));
        assertNotNull(db.statusJobs("COMPLETED", 20, 0).getFirst().get("updated_at"));
        UUID newLease = UUID.randomUUID();
        var claimed = db.claim("future-rubric-version", newLease, null).orElseThrow();
        assertEquals(job, claimed.get("id"));
        assertEquals(1, claimed.get("skill_proficiency_attempts"));
        db.fail(job, newLease, "INVALID_EVIDENCE");
        var retry = db.statusJobs("RETRY", 20, 0).getFirst();
        assertEquals("INVALID_EVIDENCE", retry.get("error_code"));
        assertNotNull(retry.get("retry_at"));
        assertEquals(1, db.retry(job));
        assertEquals(0, db.retry(job));
        for (int attempt = 2; attempt <= 3; attempt++) {
            UUID retryLease = UUID.randomUUID();
            var next = db.claim("future-rubric-version", retryLease, null).orElseThrow();
            assertEquals(attempt, next.get("skill_proficiency_attempts"));
            assertEquals(0, db.retry(job));
            db.fail(job, retryLease, "INVALID_EVIDENCE");
            if (attempt == 2)
                assertEquals(1, db.retry(job));
        }
        assertEquals(1, db.retryFailed());
        assertEquals(0, db.retryFailed());
        assertEquals(0, db.statusJobs("RETRY", 20, 0).getFirst().get("attempts"));
    }

    private static Object skill(
        String id,
        String evidence) {
        return fields(
            "skill_id",
            id,
            "required_level",
            2,
            "evidence",
            evidence,
            "reason",
            "Independent work",
            "confidence",
            0.9
        );
    }
}
