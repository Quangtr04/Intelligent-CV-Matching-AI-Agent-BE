package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Explainable rule evidence; neither similarity nor AI score is blended into coverage. */
public final class RecommendationRules {

    public static final String VERSION = "recommendation-rules-v1";

    private static final Pattern YEARS = Pattern.compile(
        ("(?<![\\p{L}\\d])(\\d{1,2}(?:\\.\\d+)?)\\s*(?:[-–]\\s*\\d{1,"
            + "2}(?:\\.\\d+)?)?\\s*\\+?\\s*(?:years?|yrs?|nam)\\b")
    );

    private RecommendationRules() {
    }

    public static Map<String, Object> assess(
        CandidateSearchProfile.Document candidate,
        Map<String, Object> job) {
        var assessment = new LinkedHashMap<>(QuickJobMatcher.assess(candidate.evidence(), job));
        var extraction = JobRequirementExtractor.forJob(job);
        Set<String> known = new HashSet<>();
        candidate.evidence().skills().forEach(name -> known.add(SkillCatalog.get().comparisonKey(name)));
        int preferred = 0, preferredMet = 0;
        List<Map<String, Object>> experience = new ArrayList<>();
        List<Map<String, Object>> unknown = new ArrayList<>();
        for (var requirement : extraction.requirements()) {
            if (!requirement.status().equals("RECOGNIZED"))
                unknown.add(fields("text", requirement.text(), "status", "UNVERIFIED"));
            if (requirement.importance().equals("PREFERRED") && !requirement.skills().isEmpty()) {
                preferred++;
                var matches = requirement.skills().stream()
                    .map(skill -> known.contains(SkillCatalog.get().comparisonKey(skill.name()))).toList();
                if (requirement.relation().equals("OR") ? matches.contains(true) : !matches.contains(false))
                    preferredMet++;
            }
            if (!requirement.importance().equals("MANDATORY"))
                continue;
            var matcher = YEARS.matcher(JobRequirementExtractor.fold(requirement.text()));
            if (!matcher.find())
                continue;
            double required = Double.parseDouble(matcher.group(1));
            boolean ambiguous = matcher.find() || !requirement.relation().equals("AND");
            Double years = requirement.skills().isEmpty()
                ? candidate.evidence().yearsExperience()
                : relevantYears(candidate, requirement);
            String status = ambiguous || years == null ? "UNVERIFIED" : years < required ? "INSUFFICIENT" : "MATCHED";
            experience.add(
                fields(
                    "text",
                    requirement.text(),
                    "required_years",
                    required,
                    "candidate_years",
                    years,
                    "status",
                    status,
                    "basis",
                    requirement.skills().isEmpty() ? "TOTAL_EMPLOYMENT" : "RELEVANT_EVIDENCE"
                )
            );
        }
        String experienceStatus = experience.stream().anyMatch(row -> "INSUFFICIENT".equals(row.get("status")))
            ? "INSUFFICIENT"
            : experience.stream().anyMatch(row -> "UNVERIFIED".equals(row.get("status")))
                ? "UNVERIFIED"
                : experience.isEmpty()
                    ? Set.of("UNVERIFIED", "INSUFFICIENT").contains(text(assessment.get("experience_status")))
                        ? "UNVERIFIED"
                        : "NOT_SPECIFIED"
                    : "MATCHED";
        assessment.put("experience_status", experienceStatus);
        assessment.put("experience_conditions", experience);
        assessment.put("preferred_coverage", preferred == 0 ? null : (double) preferredMet / preferred);
        assessment.put("unverified_requirement_details", unknown);
        assessment.put("jd_requirements", extraction.requirements());
        assessment.put("location_assessment", preference(candidate, job, "location", "location"));
        assessment.put("employment_assessment", preference(candidate, job, "employment_type", "employment_type"));
        String seniority = text(job.get("parsed_seniority"));
        String declaredSeniority = text(candidate.profile().get("seniority"));
        assessment.put(
            "seniority_assessment",
            seniority.isBlank()
                ? "NOT_SPECIFIED"
                : declaredSeniority.isBlank()
                    ? "UNVERIFIED"
                    : seniority.equalsIgnoreCase(declaredSeniority) ? "MATCHED" : "DIFFERENT_DECLARATION"
        );
        boolean educationRequired = extraction.requirements().stream()
            .anyMatch(row -> row.types().contains("EDUCATION") && row.importance().equals("MANDATORY"));
        assessment.put("education_assessment", educationRequired ? "UNVERIFIED" : "NOT_SPECIFIED");
        assessment.put("score_kind", "RECOMMENDATION_RULE_COVERAGE");
        assessment.put(
            "eligible_for_ai",
            number(assessment.get("score"), 0) > 0 && !experienceStatus.equals("INSUFFICIENT")
                && Boolean.TRUE.equals(assessment.get("requirement_coverage_available"))
        );
        assessment.put(
            "reason",
            text(assessment.get("reason")) + ". Kinh nghiệm: " + experienceStatus
                + ". Chưa có bằng chứng không đồng nghĩa không có năng lực."
        );
        return assessment;
    }

    private static Double relevantYears(
        CandidateSearchProfile.Document candidate,
        JobRequirementExtractor.Requirement requirement) {
        Set<String> required = new HashSet<>();
        requirement.skills().forEach(skill -> required.add(SkillCatalog.get().comparisonKey(skill.name())));
        List<Map<String, Object>> relevant = new ArrayList<>();
        for (Object raw : array(candidate.sourceData().get("experience"))) {
            var row = object(raw);
            String facts = text(row.get("job_title")) + "\n" + text(row.get("description"));
            Set<String> names = new HashSet<>();
            SkillCatalog.get().mentions(facts)
                .forEach(mention -> names.add(SkillCatalog.get().comparisonKey(mention.skill().name())));
            if (names.containsAll(required))
                relevant.add(row);
        }
        if (!relevant.isEmpty())
            return QuickJobMatcher.fromResume(fields("structured_data", fields("experience", relevant)))
                .yearsExperience();
        List<Double> declared = new ArrayList<>();
        for (String skill : required) {
            Double years = array(candidate.sourceData().get("skills")).stream().map(value -> object(value))
                .filter(row -> SkillCatalog.get().comparisonKey(text(row.get("name"))).equals(skill))
                .filter(row -> row.get("years_experience") instanceof Number)
                .map(row -> number(row.get("years_experience"), -1))
                .filter(value -> value >= 0 && Double.isFinite(value)).findFirst().orElse(null);
            if (years == null)
                return null;
            declared.add(years);
        }
        return declared.stream().min(Double::compareTo).orElse(null);
    }

    private static String preference(
        CandidateSearchProfile.Document candidate,
        Map<String, Object> job,
        String key,
        String field) {
        String wanted = text(object(candidate.profile().get("preferences")).get(key));
        if (wanted.isBlank())
            return "NOT_SPECIFIED";
        String actual = text(job.get(field));
        if (actual.isBlank())
            return "UNVERIFIED";
        if (key.equals("location")) {
            wanted = Objects.toString(JobNormalizer.normalizeLocation(wanted), "");
            actual = Objects.toString(JobNormalizer.normalizeLocation(actual), "");
        }
        return JobRequirementExtractor.fold(wanted).contains(JobRequirementExtractor.fold(actual))
            || JobRequirementExtractor.fold(actual).contains(JobRequirementExtractor.fold(wanted))
                ? "MATCHED"
                : "DIFFERENT_PREFERENCE";
    }

    public static Comparator<Map<String, Object>> ranking() {
        return Comparator
            .<Map<String, Object>>comparingDouble(
                job -> number(job.getOrDefault("rule_score", job.get("final_score")), 0)
            ).reversed()
            .thenComparingInt(
                job -> "INSUFFICIENT".equals(object(job.get("rule_assessment")).get("experience_status")) ? 1 : 0
            )
            .thenComparing(
                Comparator.<Map<String, Object>>comparingDouble(
                    job -> number(object(job.get("rule_assessment")).get("preferred_coverage"), 0)
                ).reversed()
            )
            .thenComparingInt(
                job -> "DIFFERENT_PREFERENCE".equals(object(job.get("rule_assessment")).get("location_assessment"))
                    ? 1
                    : 0
            )
            .thenComparingInt(
                job -> "DIFFERENT_PREFERENCE".equals(object(job.get("rule_assessment")).get("employment_assessment"))
                    ? 1
                    : 0
            )
            .thenComparing(
                Comparator.<Map<String, Object>>comparingDouble(job -> number(job.get("advisory_score"), -1)).reversed()
            )
            .thenComparing(
                Comparator.<Map<String, Object>>comparingDouble(job -> number(job.get("vector_score"), -1)).reversed()
            ).thenComparing(job -> text(job.get("id")));
    }
}
