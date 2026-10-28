package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.AssessmentValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentValidatorTests {

    @Test
    void acceptsTenGroundedQuestionsWithCanonicalWhitespaceAndCaseInsensitiveSkillIdentity() {
        var quiz = questions();
        quiz.getFirst().put("related_skill_name", "  JAVA ");
        quiz.getFirst().put("jd_evidence", "Build   Java\nbackend services");
        assertDoesNotThrow(
            () -> AssessmentValidator.validateQuestions(
                quiz,
                List.of(Map.of("skill_name", "Java")),
                List.of("Build Java backend services and use SQL safely.")
            )
        );
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "zero",
            "nine",
            "eleven",
            "unbalanced",
            "fractional",
            "duplicate",
            "null-skill",
            "blank-skill",
            "blank-question",
            "object-question",
            "wrong-option",
            "duplicate-option",
            "object-option",
            "extra-option",
            "blank-explanation",
            "no-evidence",
            "short-evidence",
            "fake-evidence",
            "other-skill"}
    )
    void rejectsInvalidQuizStructureOrUnrelatedEvidence(
        String kind) {
        var quiz = questions();
        var question = quiz.getFirst();
        switch (kind) {
            case "zero" -> quiz.clear();
            case "nine" -> quiz.removeLast();
            case "eleven" -> quiz.add(new LinkedHashMap<>(question));
            case "unbalanced" -> quiz.get(5).put("part", 1);
            case "fractional" -> question.put("part", 1.5);
            case "duplicate" -> quiz.get(1).put("question_text", "  java   question 0 ");
            case "null-skill" -> question.put("related_skill_name", null);
            case "blank-skill" -> question.put("related_skill_name", " ");
            case "blank-question" -> question.put("question_text", " ");
            case "object-question" -> question.put("question_text", Map.of("text", "Invalid"));
            case "wrong-option" -> question.put("correct_option", "E");
            case "duplicate-option" -> question.put("options", Map.of("A", "same", "B", " SAME ", "C", "C", "D", "D"));
            case "object-option" ->
                question.put("options", Map.of("A", Map.of("text", "Invalid"), "B", "B", "C", "C", "D", "D"));
            case "extra-option" -> question.put("options", Map.of("A", "A", "B", "B", "C", "C", "D", "D", "E", "E"));
            case "blank-explanation" -> question.put("explanation", " ");
            case "no-evidence" -> question.remove("jd_evidence");
            case "short-evidence" -> question.put("jd_evidence", "Java");
            case "fake-evidence" -> question.put("jd_evidence", "Develop React components");
            case "other-skill" -> question.put("related_skill_name", "JavaScript");
            default -> throw new AssertionError(kind);
        }
        var exception = assertThrows(
            ApiException.class,
            () -> AssessmentValidator.validateQuestions(
                quiz,
                List.of(Map.of("skill_name", "Java")),
                List.of("Build Java backend services and use SQL safely.")
            )
        );
        assertEquals(502, exception.status());
    }

    @Test
    void skillPunctuationDoesNotConflateDifferentLanguages() {
        var quiz = questions();
        quiz.forEach(question -> question.put("related_skill_name", "C++"));
        assertThrows(
            ApiException.class,
            () -> AssessmentValidator.validateQuestions(
                quiz,
                List.of(Map.of("skill_name", "C#")),
                List.of("Build Java backend services and use SQL safely.")
            )
        );
    }

    private List<Map<String, Object>> questions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            result.add(
                new LinkedHashMap<>(
                    Map.of(
                        "part",
                        index < 5 ? 1 : 2,
                        "question_text",
                        "Java question " + index,
                        "options",
                        Map.of("A", "Correct", "B", "Wrong", "C", "Skip validation", "D", "Ignore errors"),
                        "correct_option",
                        "A",
                        "related_skill_name",
                        "Java",
                        "jd_evidence",
                        "Build Java backend services",
                        "explanation",
                        "A follows the Java requirement in the selected job description."
                    )
                )
            );
        }
        return result;
    }
}
