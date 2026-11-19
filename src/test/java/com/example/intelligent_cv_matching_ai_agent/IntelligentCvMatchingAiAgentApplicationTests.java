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
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@Testcontainers(disabledWithoutDocker = true)
class IntelligentCvMatchingAiAgentApplicationTests {

    @Autowired
    SqlStore db;

    @Test
    void contextLoads() {
        assertEquals(
            99,
            ((Number) db.scalar(
                ("SELECT count(*) FROM information_schema.tables WHERE "
                    + "table_schema='public' AND table_type='BASE TABLE' AND " + "table_name<>'flyway_schema_history'")
            )).intValue()
        );
    }

    @Test
    void pgvectorDimensionsAndCosineWorkAfterFlyway() {
        String[] parts = new String[1024];
        Arrays.fill(parts, "0");
        parts[0] = "1";
        String vector = "[" + String.join(",", parts) + "]";
        assertEquals(1024, ((Number) db.scalar("SELECT vector_dims(CAST(? AS vector(1024)))", vector)).intValue());
        assertEquals(
            1.0,
            ((Number) db.scalar("SELECT 1-(CAST(? AS vector)<=>CAST(? AS vector))", vector, vector)).doubleValue(),
            1e-7
        );
        assertThrows(DataAccessException.class, () -> db.scalar("SELECT CAST(? AS vector(1024))", "[1,0,0]"));
    }

}
