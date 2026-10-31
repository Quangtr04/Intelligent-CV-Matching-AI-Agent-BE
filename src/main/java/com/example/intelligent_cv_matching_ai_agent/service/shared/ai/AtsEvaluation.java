package com.example.intelligent_cv_matching_ai_agent.service.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.AtsScoring;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.array;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/**
 * Job ATS evaluation: the score always comes from the deterministic {@link AtsScoring} rubric. The local model only
 * rewrites strengths and suggestions; when it is disabled or fails, the rubric's own feedback is returned.
 */
public final class AtsEvaluation {

    private static final int MAX_STRENGTHS = 6;

    private static final int MAX_SUGGESTIONS = 8;

    private AtsEvaluation() {
    }

    public static Map<String, Object> evaluate(
        AiService ai,
        Map<String, Object> resume,
        String resumeText,
        Map<String, Object> job,
        List<Map<String, Object>> jobSkills,
        String language) {
        Map<String, Object> source = new LinkedHashMap<>(resume);
        source.put("language", language);
        Map<String, Object> report = AtsScoring.score(source, job, jobSkills);

        List<String> strengths = strings(report.get("strengths"), MAX_STRENGTHS);
        List<String> suggestions = new ArrayList<>();
        for (Object item : array(report.get("suggestions")))
            if (suggestions.size() < MAX_SUGGESTIONS)
                suggestions.add(text(object(item).get("explanation")));
        List<String> missing = new ArrayList<>();
        for (Object item : array(report.get("missing_skills")))
            missing.add(text(object(item).get("name")));
        String model = AtsScoring.RUBRIC_VERSION;
        String feedbackSource = "RULES";

        if (ai.enabled()) {
            try {
                Map<String, Object> generated = ai.generate(
                    "JOB_ATS",
                    fields(
                        "resume",
                        resume.get("structured_data"),
                        "resume_text",
                        resumeText,
                        "job",
                        job,
                        "required_skills",
                        jobSkills,
                        "language",
                        language,
                        "ats_report",
                        summary(report)
                    )
                );
                List<String> aiStrengths = strings(generated.get("strengths"), MAX_STRENGTHS);
                List<String> aiSuggestions = strings(generated.get("improvement_suggestions"), MAX_SUGGESTIONS);
                if (!aiStrengths.isEmpty())
                    strengths = aiStrengths;
                if (!aiSuggestions.isEmpty())
                    suggestions = aiSuggestions;
                String generatedModel = text(generated.get("_model"));
                model = (generatedModel.isBlank() ? ai.model() : generatedModel) + " + " + AtsScoring.RUBRIC_VERSION;
                feedbackSource = "AI";
            } catch (ApiException ex) {
                // Quota and validation errors stay visible; provider failures fall back to rule feedback.
                if (ex.status() < 500)
                    throw ex;
            }
        }
        report.put("feedback_source", feedbackSource);

        return fields(
            "ats_score",
            report.get("score"),
            "ats_report",
            report,
            "strengths",
            strengths,
            "missing_skills",
            missing,
            "improvement_suggestions",
            suggestions,
            "model_name",
            model.length() > 150 ? model.substring(0, 150) : model
        );
    }

    /** The compact view of the rubric the model needs; the score is final and only explained. */
    private static Map<String, Object> summary(
        Map<String, Object> report) {
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Object item : array(report.get("categories"))) {
            Map<String, Object> category = object(item);
            List<Map<String, Object>> failed = new ArrayList<>();
            for (Object check : array(category.get("checks")))
                if (Boolean.FALSE.equals(object(check).get("passed")))
                    failed.add(fields("check", object(check).get("label"), "detail", object(check).get("detail")));
            categories.add(
                fields("category", category.get("label"), "score", category.get("score"), "failed_checks", failed)
            );
        }
        return fields(
            "final_score",
            report.get("score"),
            "band",
            report.get("band"),
            "categories",
            categories,
            "matched_skills",
            report.get("matched_skills"),
            "missing_skills",
            report.get("missing_skills"),
            "issues",
            report.get("issues")
        );
    }

    private static List<String> strings(
        Object value,
        int limit) {
        List<String> result = new ArrayList<>();
        for (Object item : array(value)) {
            String line = text(item).strip();
            if (!line.isBlank() && line.length() <= 2000 && result.size() < limit)
                result.add(line);
        }
        return result;
    }
}
