package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Evidence lower bound, not a probability of suitability. Optional clauses do not enter the denominator. */
public final class RequirementCoverage {

    private RequirementCoverage() {
    }

    public static Map<String, Object> assess(
        Set<String> candidate,
        Map<String, Object> job) {
        var extracted = JobRequirementExtractor.forJob(job);
        Set<String> normalized = new HashSet<>();
        for (String name : candidate) {
            normalized.add(
                com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get().comparisonKey(name)
            );
            JobRequirementExtractor.recognize(name).forEach(
                s -> normalized.add(
                    com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                        .comparisonKey(s.name())
                )
            );
        }
        boolean coverageAvailable = !extracted.requirements().isEmpty() && !extracted.status().equals("TRUNCATED");
        int total = 0, matched = 0, unverified = 0;
        List<String> gaps = new ArrayList<>(), strengths = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (var row : extracted.requirements()) {
            if (Set.of("PREFERRED", "FLEXIBLE", "RESPONSIBILITY").contains(row.importance()))
                continue;
            if (Set.of("CONDITIONAL", "UNSPECIFIED").contains(row.importance())
                || !Set.of("AND", "OR").contains(row.relation())) {
                total++;
                unverified++;
                continue;
            }
            if (!row.status().equals("RECOGNIZED")) {
                total++;
                unverified++;
            }
            if (row.relation().equals("OR")) {
                String key = row.skills().stream()
                    .map(
                        s -> com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                            .comparisonKey(s.name())
                    ).sorted().collect(java.util.stream.Collectors.joining(" OR "));
                if (!key.isBlank() && counted.add(key)) {
                    total++;
                    if (row.skills().stream().anyMatch(
                        s -> normalized.contains(
                            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                                .comparisonKey(s.name())
                        )
                    )) {
                        matched++;
                        strengths.add(key);
                    } else
                        gaps.add(key);
                }
            } else {
                for (var skill : row.skills()) {
                    String key = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                        .comparisonKey(skill.name());
                    if (!counted.add(key))
                        continue;
                    total++;
                    if (normalized.contains(key)) {
                        matched++;
                        strengths.add(key);
                    } else
                        gaps.add(key);
                }
            }
        }
        // Preserve explicit/manual skill data when original text is unavailable or omits the tag.
        for (Object raw : array(job.get("skills"))) {
            var row = object(raw);
            String key = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                .comparisonKey(text(row.get("name")));
            boolean represented = extracted.requirements().stream().anyMatch(
                r -> r.skills().stream().anyMatch(
                    s -> com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                        .comparisonKey(s.name()).equals(key)
                )
            );
            if (key.isBlank() || represented || Boolean.FALSE.equals(row.get("required")) || !counted.add(key))
                continue;
            total++;
            if (normalized.contains(key)) {
                matched++;
                strengths.add(key);
            } else
                gaps.add(key);
        }
        double coverage = total == 0 ? 0 : (double) matched / total;
        return fields(
            "score",
            coverage,
            "requirement_coverage",
            coverageAvailable ? coverage : null,
            "requirement_coverage_available",
            coverageAvailable,
            "skill_coverage",
            total == unverified ? 0.0 : (double) matched / (total - unverified),
            "reason",
            coverageAvailable
                ? "Đối chiếu " + matched + "/" + total + " đơn vị yêu cầu; " + unverified + " chưa xác minh"
                : "Chỉ đối chiếu kỹ năng khai báo; chưa đủ nguồn để tính độ bao phủ yêu cầu JD",
            "strengths",
            strengths,
            "skill_gaps",
            gaps,
            "unverified_requirements",
            unverified,
            "extraction_status",
            extracted.status(),
            "extraction_version",
            extracted.version(),
            "requirements",
            extracted.requirements(),
            "catalog_version",
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get().version(),
            "coverage_complete",
            extracted.status().equals("PARSED") && unverified == 0,
            "score_kind",
            coverageAvailable ? "REQUIREMENT_EVIDENCE_COVERAGE" : "DECLARED_SKILL_COVERAGE_ONLY"
        );
    }
}
