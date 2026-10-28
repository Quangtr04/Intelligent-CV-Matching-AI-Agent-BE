package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;
import static org.junit.jupiter.api.Assertions.*;

class JobMatchResponseSchemaTests {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void requiresExactlyOneCompactResultForEveryJobInTheBatch(
        int count) {
        List<UUID> ids = ids(count);
        var root = JobMatchResponseSchema.schema(Map.of("jobs", jobs(ids)));
        assertEquals("object", root.get("type"));
        assertEquals(false, root.get("additionalProperties"));
        assertEquals(List.of("results"), root.get("required"));
        var results = object(object(root.get("properties")).get("results"));
        assertEquals("array", results.get("type"));
        assertEquals(count, results.get("minItems"));
        assertEquals(count, results.get("maxItems"));
        var result = object(results.get("items"));
        assertEquals(false, result.get("additionalProperties"));
        assertEquals(List.of("job_id", "reason", "score", "skill_gaps", "strengths"), result.get("required"));
        var properties = object(result.get("properties"));
        var jobId = object(properties.get("job_id"));
        assertEquals("string", jobId.get("type"));
        assertEquals(ids.stream().map(UUID::toString).toList(), jobId.get("enum"));
        assertEquals(Map.of("type", "number", "minimum", 0, "maximum", 1), properties.get("score"));
        assertEquals(Map.of("type", "string", "minLength", 1, "maxLength", 240), properties.get("reason"));
        for (String key : List.of("strengths", "skill_gaps")) {
            var list = object(properties.get(key));
            assertEquals("array", list.get("type"));
            assertEquals(0, list.get("minItems"));
            assertEquals(5, list.get("maxItems"));
            assertEquals(Map.of("type", "string", "minLength", 1, "maxLength", 100), list.get("items"));
        }
    }

    @Test
    void producesAJsonSerializableSchemaWithUuidStringsAndNoUnrequestedFields() {
        var mapper = JsonMapper.builder().build();
        var ids = ids(2);
        var schema = JobMatchResponseSchema.schema(Map.of("jobs", jobs(ids)));
        String encoded = mapper.writeValueAsString(schema);
        var decoded = mapper.readTree(encoded);
        assertEquals(
            ids.getFirst().toString(),
            decoded.at("/properties/results/items/properties/job_id/enum/0").asText()
        );
        assertEquals(5, decoded.at("/properties/results/items/properties").size());
        assertFalse(decoded.at("/additionalProperties").asBoolean());
        assertFalse(decoded.at("/properties/results/items/additionalProperties").asBoolean());
    }

    @Test
    void acceptsCanonicalStringIdsAsWellAsUuidObjects() {
        String id = ids(1).getFirst().toString();
        var schema = JobMatchResponseSchema.schema(Map.of("jobs", List.of(Map.of("id", id))));
        var results = object(object(schema.get("properties")).get("results"));
        var properties = object(object(results.get("items")).get("properties"));
        assertEquals(List.of(id), object(properties.get("job_id")).get("enum"));
    }

    @Test
    void rejectsAbsentEmptyOversizedDuplicateAndInvalidJobContexts() {
        assertThrows(IllegalArgumentException.class, () -> JobMatchResponseSchema.schema(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> JobMatchResponseSchema.schema(Map.of("jobs", List.of())));
        assertThrows(IllegalArgumentException.class, () -> JobMatchResponseSchema.schema(Map.of("jobs", jobs(ids(4)))));
        UUID id = ids(1).getFirst();
        assertThrows(
            IllegalArgumentException.class,
            () -> JobMatchResponseSchema.schema(Map.of("jobs", List.of(Map.of("id", id), Map.of("id", id))))
        );
        for (Object invalid : List.of(Map.of("id", "not-a-uuid"), Map.of("id", "1-1-1-1-1"), Map.of(), "job")) {
            assertThrows(
                IllegalArgumentException.class,
                () -> JobMatchResponseSchema.schema(Map.of("jobs", List.of(invalid)))
            );
        }
    }

    private static List<UUID> ids(
        int count) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            ids.add(UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", index)));
        }
        return ids;
    }

    private static List<Map<String, Object>> jobs(
        List<UUID> ids) {
        return ids.stream().map(id -> Map.<String, Object>of("id", id, "job_title", "Java Developer")).toList();
    }
}
