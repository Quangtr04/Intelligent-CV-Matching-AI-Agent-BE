package com.example.intelligent_cv_matching_ai_agent.util.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.text.Normalizer;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class AssessmentValidator {

    private AssessmentValidator() {
    }

    public static void validateQuestions(
        List<?> questions) {
        if (questions.size() != 10)
            throw new ApiException(502, "AI must generate exactly 10 job-description assessment questions");

        int foundation = 0, technical = 0;
        Set<String> questionTexts = new HashSet<>();

        for (Object raw : questions) {
            var q = object(raw);
            double part = number(q.get("part"), 0);

            if (part == 1)
                foundation++;
            else if (part == 2)
                technical++;
            else
                throw new ApiException(502, "Invalid assessment part");

            if (!(q.get("question_text") instanceof String question) || question.isBlank() || question.length() > 5000
                || !(q.get("related_skill_name") instanceof String skill) || skill.isBlank() || skill.length() > 150
                || !(q.get("explanation") instanceof String explanation) || explanation.isBlank()
                || explanation.length() > 10000)
                throw new ApiException(502, "Invalid question content");
            if (!questionTexts.add(SkillNames.normalize(question)))
                throw new ApiException(502, "Assessment questions must be distinct");

            Map<String, Object> options = object(q.get("options"));
            if (!options.keySet().equals(Set.of("A", "B", "C", "D"))
                || options.values().stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 2000)
                || !(q.get("correct_option") instanceof String correct) || !options.containsKey(correct))
                throw new ApiException(502, "Invalid answer choices");
            if (options.values().stream().map(v -> SkillNames.normalize((String) v)).distinct().count() != 4)
                throw new ApiException(502, "Assessment answer choices must be distinct");
        }

        if (foundation != 5 || technical != 5)
            throw new ApiException(502, "Assessment must contain 5 JD foundation and 5 JD technical questions");
    }

    public static void validateQuestions(
        List<?> questions,
        List<Map<String, Object>> jobSkills,
        List<String> jdSources) {
        validateQuestions(questions);
        Set<String> allowed = new HashSet<>();
        for (var skill : jobSkills)
            allowed.add(SkillNames.normalize(text(skill.get("skill_name"))));
        List<String> sources = jdSources.stream().map(AssessmentValidator::quoteText).toList();

        for (Object raw : questions) {
            var question = object(raw);
            if (!allowed.contains(SkillNames.normalize(text(question.get("related_skill_name")))))
                throw new ApiException(502, "Assessment question references a skill outside the selected JD");
            if (!(question.get("jd_evidence") instanceof String evidence) || evidence.strip().length() < 8
                || evidence.length() > 250)
                throw new ApiException(502, "Assessment question needs a short quote from the selected JD");
            String quote = quoteText(evidence);
            if (sources.stream().noneMatch(source -> source.contains(quote)))
                throw new ApiException(502, "Assessment evidence was not found in the selected JD");
        }
    }

    private static String quoteText(
        String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("(?U)\\s+", " ");
    }

    public static void validateCandidateEvidence(
        List<?> questions,
        String resumeText) {
        String source = quoteText(resumeText);
        for (Object raw : questions) {
            var question = object(raw);
            if (!(question.get("cv_evidence") instanceof String evidence) || evidence.length() > 250) {
                throw new ApiException(502, "CV-aware questions require a short CV evidence quote or an empty string");
            }
            if (!evidence.isBlank() && !source.contains(quoteText(evidence))) {
                throw new ApiException(502, "Assessment CV evidence was not found in the selected confirmed CV");
            }
        }
    }
}
