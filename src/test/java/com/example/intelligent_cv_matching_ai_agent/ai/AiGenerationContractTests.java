package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiFeatureCatalog;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiGenerationContractTests {

    @Test
    void roadmapConstrainsEverySkillPositionAndOnlyAllowsEarlierDependencies() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat(
            "ROADMAP_GENERATE",
            Map.of("allowed_missing_skills", List.of("Docker", "PostgreSQL", "Java"))
        );
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        assertFalse(properties.containsKey("skill_gaps"));
        Map<?, ?> nodes = (Map<?, ?>) properties.get("nodes");
        // Exact min/max length bounds the positional array.
        assertFalse(nodes.containsKey("items"));
        assertEquals(3, nodes.get("minItems"));
        assertEquals(3, nodes.get("maxItems"));
        List<?> positions = (List<?>) nodes.get("prefixItems");
        for (int i = 0; i < positions.size(); i++) {
            Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) positions.get(i)).get("properties");
            assertEquals(
                List.of("Docker", "PostgreSQL", "Java").get(i),
                ((Map<?, ?>) fields.get("skill_name")).get("const")
            );
            Map<?, ?> prerequisites = (Map<?, ?>) fields.get("prerequisites");
            assertEquals(i, prerequisites.get("maxItems"));
            assertEquals(Math.max(1, i), ((Map<?, ?>) prerequisites.get("items")).get("maximum"));
        }
        assertDoesNotThrow(
            () -> AiResponseData
                .validate("ROADMAP_GENERATE", Map.of("title", "Practice missing skills", "nodes", List.of()))
        );
        assertTrue(
            AiFeatureCatalog.defaultPrompt("ROADMAP_GENERATE").get("user_prompt_template").toString()
                .contains("do not return skill_gaps")
        );
    }

    @Test
    void applicationAssistantUsesTheSameAnswerEnvelopeAsTheMessageEndpoint() {
        Map<?, ?> schema = (Map<?, ?>) AiResponseData.outputFormat("CAREER_CHAT", Map.of());
        assertEquals(List.of("answer"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("answer"), ((Map<?, ?>) schema.get("properties")).keySet().stream().toList());
        assertTrue(
            AiFeatureCatalog.defaultPrompt("CAREER_CHAT").get("user_prompt_template").toString()
                .contains("finished requested document")
        );
        assertDoesNotThrow(() -> AiResponseData.validate("CAREER_CHAT", Map.of("answer", "Dear hiring team")));
        assertThrows(
            IllegalArgumentException.class,
            () -> AiResponseData.validate("CAREER_CHAT", Map.of("content", "Dear hiring team"))
        );
    }
}
