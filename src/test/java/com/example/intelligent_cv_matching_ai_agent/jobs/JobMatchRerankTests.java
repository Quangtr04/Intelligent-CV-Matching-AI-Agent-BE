package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.JobMatchRerank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class JobMatchRerankTests {

    @Test
    void compactBatchesPreserveIdsOrderAndSourcesWhileBoundingTextSkillsAndBatchSize() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            Map<String, Object> job = job(UUID.randomUUID());
            List<Map<String, Object>> skills = new ArrayList<>();
            for (int skill = 0; skill < 25; skill++) {
                skills.add(new LinkedHashMap<>(Map.of("name", "skill-" + skill, "required", true, "internal", "omit")));
            }
            job.put("skills", skills);
            job.put("raw_content", "a".repeat(1499) + "😀" + "b".repeat(4000));
            job.put("final_score", 0.99);
            job.put("explanation", Map.of("reason", "An internal keyword score"));
            job.put("source_url", "https://example.test/source");
            jobs.add(job);
        }
        var batches = JobMatchRerank.batches(jobs);
        assertEquals(List.of(3, 3, 1), batches.stream().map(List::size).toList());
        var compact = batches.stream().flatMap(List::stream).toList();
        assertEquals(
            jobs.stream().map(job -> job.get("id")).toList(),
            compact.stream().map(job -> job.get("id")).toList()
        );
        for (int index = 0; index < compact.size(); index++) {
            Map<String, Object> value = compact.get(index);
            assertEquals(Set.of("id", "job_title", "location", "salary_text", "raw_content", "skills"), value.keySet());
            assertTrue(value.get("raw_content").toString().length() <= 1500);
            assertFalse(Character.isHighSurrogate(value.get("raw_content").toString().charAt(1498)));
            List<?> skills = (List<?>) value.get("skills");
            assertEquals(20, skills.size());
            assertEquals(Set.of("name", "required"), ((Map<?, ?>) skills.getFirst()).keySet());
            assertTrue(jobs.get(index).get("raw_content").toString().length() > 1500);
            assertTrue(jobs.get(index).containsKey("final_score"));
            assertEquals(25, ((List<?>) jobs.get(index).get("skills")).size());
        }
        jobs.getFirst().put("job_title", "Changed later");
        assertEquals("Java Developer", compact.getFirst().get("job_title"));
        assertEquals(List.of(), JobMatchRerank.batches(List.of()));
    }

    @Test
    void validationAcceptsEveryExpectedIdOnceRegardlessOfResponseOrderAndOptionalArrays() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var batch = JobMatchRerank.batches(List.of(job(first), job(second))).getFirst();
        var result = JobMatchRerank.validate(
            batch,
            Map.of(
                "results",
                List.of(
                    Map.of("job_id", second.toString(), "score", 1, "reason", "Relevant experience"),
                    Map.of("job_id", first.toString(), "score", 0)
                )
            )
        );
        assertEquals(Set.of(first, second), result.keySet());
        assertEquals(1, result.get(second).get("score"));
        assertEquals(0, result.get(first).get("score"));
        assertEquals(Map.of(), JobMatchRerank.validate(List.of(), Map.of("results", List.of())));
    }

    @Test
    void validationRejectsDuplicateUnknownOmittedMalformedAndOtherBatchIdsWith502() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID otherBatch = UUID.randomUUID();
        var batches = JobMatchRerank.batches(List.of(job(first), job(second), job(third), job(otherBatch)));
        Map<String, Object> valid = Map.of("job_id", first.toString(), "score", 0.5);
        List<Map<String, Object>> invalid = List.of(
            Map.of("results", List.of(valid, valid)),
            Map.of("results", List.of(valid)),
            Map.of("results", List.of(Map.of("job_id", UUID.randomUUID().toString(), "score", 0.5))),
            Map.of("results", List.of(Map.of("job_id", otherBatch.toString(), "score", 0.5))),
            Map.of("results", List.of(Map.of("job_id", "not-a-uuid", "score", 0.5))),
            Map.of("results", List.of(Map.of("score", 0.5))),
            Map.of("results", List.of("unexpected-string")),
            Map.of("results", "not-an-array"),
            Map.of()
        );
        for (Map<String, Object> response : invalid) {
            assertEquals(
                502,
                assertThrows(ApiException.class, () -> JobMatchRerank.validate(batches.getFirst(), response)).status()
            );
        }
    }

    @ParameterizedTest
    @MethodSource("invalidScores")
    void validationRejectsMissingNonnumericNonfiniteAndOutOfRangeScores(
        Object score) {
        UUID id = UUID.randomUUID();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job_id", id.toString());
        result.put("score", score);
        ApiException error = assertThrows(
            ApiException.class,
            () -> JobMatchRerank.validate(List.of(job(id)), Map.of("results", List.of(result)))
        );
        assertEquals(502, error.status());
    }

    private static Stream<Arguments> invalidScores() {
        return Stream.of(null, "0.5", -0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .map(Arguments::of);
    }

    private static Map<String, Object> job(
        UUID id) {
        return new LinkedHashMap<>(
            Map.of(
                "id",
                id,
                "job_title",
                "Java Developer",
                "location",
                "Ha Noi",
                "salary_text",
                "1000 USD/month",
                "raw_content",
                "Required Java and SQL experience",
                "skills",
                List.of(Map.of("name", "java", "required", true))
            )
        );
    }
}
