package com.example.intelligent_cv_matching_ai_agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IntelligentCvMatchingAiAgentApplicationTests {

    @Autowired
    SqlStore db;

    @Test
    void contextLoads() {
        assertEquals(
            76,
            ((Number) db.scalar(
                ("SELECT count(*) FROM information_schema.tables WHERE "
                    + "table_schema='public' AND table_type='BASE TABLE' AND " + "table_name<>'flyway_schema_history'")
            )).intValue()
        );
    }

    @Test
    void pgvectorDimensionsAndCosineWorkAfterFlyway() {
        String[] parts = new String[1536];
        Arrays.fill(parts, "0");
        parts[0] = "1";
        String vector = "[" + String.join(",", parts) + "]";
        assertEquals(1536, ((Number) db.scalar("SELECT vector_dims(CAST(? AS vector(1536)))", vector)).intValue());
        assertEquals(
            1.0,
            ((Number) db.scalar("SELECT 1-(CAST(? AS vector)<=>CAST(? AS vector))", vector, vector)).doubleValue(),
            1e-7
        );
        assertThrows(DataAccessException.class, () -> db.scalar("SELECT CAST(? AS vector(1536))", "[1,0,0]"));
    }

}
