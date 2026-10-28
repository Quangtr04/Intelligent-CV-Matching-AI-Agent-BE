package com.example.intelligent_cv_matching_ai_agent.identity;

import java.math.BigDecimal;
import java.util.Collections;
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
class CareerProfileSalaryMigrationIntegrationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-bookworm").asCompatibleSubstituteFor("postgres")
    );

    @Test
    void upgradesRangesWithoutDiscardingHistoricalValuesAndInvalidatesOldEmbeddings() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .target("11").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        );
        UUID[] users = new UUID[6];
        Object[][] ranges = {{7000000, 8000000}, {7500000, null}, {null, 7500000}, {0, 0}, {null, null}, {0, 7500000}};
        for (int index = 0; index < users.length; index++) {
            users[index] = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO users(id,email,full_name,password_hash,status) "
                    + "VALUES(?,?,'Salary Migration Test','test-only-unusable-hash','ACTIVE')",
                users[index],
                "salary-migration-" + index + "@example.test"
            );
            jdbc.update(
                "INSERT INTO candidate_profiles(user_id,expected_salary_min,expected_salary_max) VALUES(?,?,?)",
                users[index],
                ranges[index][0],
                ranges[index][1]
            );
        }
        jdbc.update(
            "INSERT INTO career_profile_embeddings(user_id,provider,model,embedding,is_current) "
                + "VALUES(?,'ollama','qwen3-embedding:0.6b',?::vector,true)",
            users[0],
            "[1," + String.join(",", Collections.nCopies(1023, "0")) + "]"
        );

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load()
            .migrate();

        for (int index = 0; index < users.length; index++) {
            var row = jdbc.queryForMap("SELECT * FROM candidate_profiles WHERE user_id=?", users[index]);
            if (index == 3 || index == 4) {
                assertNull(row.get("expected_salary"));
            } else {
                assertEquals(0, new BigDecimal("7500000").compareTo((BigDecimal) row.get("expected_salary")));
            }
            for (int bound = 0; bound < 2; bound++) {
                String column = bound == 0 ? "expected_salary_min" : "expected_salary_max";
                if (ranges[index][bound] == null) {
                    assertNull(row.get(column));
                } else {
                    assertEquals(
                        0,
                        new BigDecimal(ranges[index][bound].toString()).compareTo((BigDecimal) row.get(column))
                    );
                }
            }
        }
        assertFalse(
            jdbc.queryForObject(
                "SELECT is_current FROM career_profile_embeddings WHERE user_id=?",
                Boolean.class,
                users[0]
            )
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE candidate_profiles SET expected_salary=0 WHERE user_id=?", users[0])
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE candidate_profiles SET expected_salary=-1 WHERE user_id=?", users[0])
        );
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("UPDATE candidate_profiles SET expected_salary='NaN'::numeric WHERE user_id=?", users[0])
        );
    }
}
