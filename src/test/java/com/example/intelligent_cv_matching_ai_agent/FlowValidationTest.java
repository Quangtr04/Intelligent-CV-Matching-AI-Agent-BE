package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.LearningService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowValidationTest {

    @Test
    void jobSourceRejectsLookalikeHostsAndCredentials() {
        assertThrows(
            ApiException.class,
            () -> JobSourceValidator.validateExternalUrl("https://itviec.com.attacker.test/job")
        );
        assertThrows(ApiException.class, () -> JobSourceValidator.validateExternalUrl("https://admin@topcv.vn/job"));
        assertThrows(ApiException.class, () -> JobSourceValidator.validateExternalUrl("http://topcv.vn/job"));
        assertEquals("topcv.vn", JobSourceValidator.validateExternalUrl("https://topcv.vn/viec-lam/java").getHost());
    }

    @Test
    void overlapUsesRequiredSkillsAndWordBoundaries() {
        var job = Map.<String, Object>of(
            "skills",
            List.of(
                Map.of("name", "Java", "required", true),
                Map.of("name", "SQL", "required", true),
                Map.of("name", "Docker", "required", false)
            )
        );
        assertEquals(0.5, MatchingService.overlap("JavaScript SQL Docker", job).get("score"));
        assertEquals(List.of("java"), MatchingService.overlap("JavaScript SQL Docker", job).get("skill_gaps"));
    }

    @Test
    void roadmapRejectsCyclesForwardReferencesAndFractionalHours() {
        assertThrows(
            ApiException.class,
            () -> LearningService.validateNodes(
                List.of(Map.of("skill_name", "Java", "estimated_hours", 10, "prerequisites", List.of(1)))
            )
        );
        assertThrows(
            ApiException.class,
            () -> LearningService.validateNodes(
                List.of(Map.of("skill_name", "Java", "estimated_hours", 1.5, "prerequisites", List.of()))
            )
        );
        assertDoesNotThrow(
            () -> LearningService.validateNodes(
                List.of(
                    Map.of("skill_name", "Java", "estimated_hours", 10),
                    Map.of("skill_name", "Spring", "estimated_hours", 20, "prerequisites", List.of(1))
                )
            )
        );
    }

    @Test
    void assessmentRejectsAnswerNotAmongChoices() {
        var foundation = Map.of(
            "part",
            1,
            "question_text",
            "Foundation?",
            "options",
            Map.of("A", "a", "B", "b", "C", "c", "D", "d"),
            "correct_option",
            "E"
        );
        var technical = Map.of(
            "part",
            2,
            "question_text",
            "Technical?",
            "options",
            Map.of("A", "a", "B", "b", "C", "c", "D", "d"),
            "correct_option",
            "A"
        );
        assertThrows(ApiException.class, () -> AssessmentService.validateQuestions(List.of(foundation, technical)));
    }

    @Test
    void assessmentRejectsObjectWhereQuestionTextIsRequired() {
        var invalid = Map.of(
            "part",
            1,
            "question_text",
            Map.of("unexpected", "object"),
            "options",
            Map.of("A", "a", "B", "b", "C", "c", "D", "d"),
            "correct_option",
            "A"
        );
        var technical = Map.of(
            "part",
            2,
            "question_text",
            "Technical?",
            "options",
            Map.of("A", "a", "B", "b", "C", "c", "D", "d"),
            "correct_option",
            "A"
        );
        assertThrows(ApiException.class, () -> AssessmentService.validateQuestions(List.of(invalid, technical)));
    }

    @Test
    void roadmapRejectsMalformedPrerequisitesAndText() {
        assertThrows(
            ApiException.class,
            () -> LearningService
                .validateNodes(List.of(Map.of("skill_name", "Java", "estimated_hours", 10, "prerequisites", "1")))
        );
        assertThrows(
            ApiException.class,
            () -> LearningService.validateNodes(
                List.of(
                    Map.of(
                        "skill_name",
                        "Java",
                        "estimated_hours",
                        10,
                        "description",
                        Map.of("body", "Unexpected object")
                    )
                )
            )
        );
        assertThrows(
            ApiException.class,
            () -> LearningService.validateGaps(
                List.of(
                    Map.of(
                        "skill_name",
                        "Java",
                        "gap_status",
                        "MISSING",
                        "urgency_score",
                        80,
                        "reasoning",
                        List.of("Unexpected list")
                    )
                )
            )
        );
        assertDoesNotThrow(
            () -> LearningService.validateGaps(
                List.of(
                    Map.of(
                        "skill_name",
                        "Java",
                        "gap_status",
                        "MISSING",
                        "urgency_score",
                        80,
                        "reasoning",
                        "Required for target role"
                    )
                )
            )
        );
    }
}
