package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Source-bound evidence, retrieval and scoring shared by recommendations and a selected CV/JD pair. */
public final class RequirementEvidence {

    public static final String VERSION = "requirement-evidence-v1";

    public static final String FEATURE = "JOB_REQUIREMENT_EVIDENCE";

    public record Chunk(
        String id,
        String source,
        String text
    ) {
    }

    public record Requirement(
        String id,
        String text,
        String importance,
        String relation,
        List<String> skills
    ) {
    }

    private RequirementEvidence() {
    }

    public static boolean durationConstraint(
        String requirement) {
        return java.util.regex.Pattern.compile(
            ("\\b(?:\\d+(?:[.," + "]\\d+)?|one|two|three|four|five|mot|hai|ba|bon|nam)\\s*(?:\\+|[-–]\\s*"
                + "\\d+)?\\s*(?:years?|nam)\\b")
        ).matcher(JobRequirementExtractor.fold(requirement)).find();
    }

    public static List<Chunk> resumeChunks(
        List<Map<String, Object>> resumes) {
        List<Chunk> result = new ArrayList<>();
        for (var resume : resumes) {
            var data = object(resume.get("structured_data"));
            String prefix = "resume:" + text(resume.get("id"));
            add(result, prefix + "/summary", text(data.get("summary")));
            for (String section : List.of("skills", "experience", "projects", "education", "certifications")) {
                List<?> rows = array(data.get(section));
                for (int i = 0; i < rows.size(); i++) {
                    Object raw = rows.get(i);
                    String source = prefix + "/" + section + "/" + i;
                    if (raw instanceof String value) {
                        add(result, source, value);
                        continue;
                    }
                    var row = object(raw);
                    List<String> keys = switch (section) {
                        case "skills" -> List.of("name", "proficiency_level", "years_experience");
                        case "experience" ->
                            List.of("job_title", "start_date", "end_date", "is_current", "description");
                        case "projects" -> List.of("project_name", "role", "description", "technologies", "highlights");
                        case "education" ->
                            List.of("school_name", "degree", "major", "start_date", "end_date", "description");
                        default -> List.of("name", "issuer", "issue_date", "expiry_date");
                    };
                    StringJoiner content = new StringJoiner("\n");
                    for (String key : keys) {
                        String value = text(row.get(key)).strip();
                        if (!value.isBlank())
                            content.add(key + ": " + value);
                    }
                    add(result, source, content.toString());
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<Chunk> profileChunks(
        Map<String, Object> profile,
        List<Map<String, Object>> skills) {
        List<Chunk> result = new ArrayList<>();
        for (String key : List.of("current_job_title", "profile_summary", "years_experience")) {
            add(result, "profile/" + key, text(profile.get(key)));
        }
        for (int i = 0; i < skills.size(); i++)
            add(result, "profile/skills/" + i, text(skills.get(i).get("name")));
        return List.copyOf(result);
    }

    private static void add(
        List<Chunk> chunks,
        String source,
        String value) {
        String clean = value.strip();
        // Split rather than silently discard long evidence. Source offsets remain inspectable.
        for (int start = 0; start < clean.length();) {
            int end = Math.min(start + 1400, clean.length());
            if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1)))
                end--;
            String part = clean.substring(start, end);
            chunks.add(new Chunk("c" + chunks.size(), source + "/offset:" + start, part));
            if (chunks.size() > 1000)
                throw ApiException.badRequest("Too much CV evidence; select fewer confirmed CVs");
            start = end;
        }
    }

    public static List<Requirement> requirements(
        Map<String, Object> job) {
        var extraction = JobRequirementExtractor.forJob(job);
        List<Requirement> result = new ArrayList<>();
        Set<String> represented = new HashSet<>();
        Set<String> clauses = new HashSet<>();
        var sourceRows = new ArrayList<>(extraction.requirements());
        if (!text(job.get("parsed_responsibilities")).isBlank())
            sourceRows.addAll(
                JobRequirementExtractor.extract(
                    "",
                    "",
                    "<h3>Responsibilities</h3><div>" + text(job.get("parsed_responsibilities")) + "</div>"
                ).requirements()
            );
        for (var row : sourceRows) {
            if (!clauses.add(row.importance() + ":" + row.relation() + ":" + row.text().strip()))
                continue;
            List<String> skills = row.skills().stream().map(JobRequirementExtractor.Skill::name).toList();
            skills.forEach(name -> represented.add(SkillCatalog.get().comparisonKey(name)));
            result.add(new Requirement("r" + result.size(), row.text(), row.importance(), row.relation(), skills));
        }
        // Manual tags remain usable when source text has no corresponding clause. Do not double count them.
        for (Object raw : array(job.get("skills"))) {
            var row = object(raw);
            String name = text(row.getOrDefault("name", row.get("skill_name"))).strip();
            if (!name.isBlank() && represented.add(SkillCatalog.get().comparisonKey(name))) {
                result.add(
                    new Requirement(
                        "r" + result.size(),
                        name,
                        Boolean.FALSE.equals(row.getOrDefault("required", row.get("is_required")))
                            ? "PREFERRED"
                            : "MANDATORY",
                        "AND",
                        List.of(name)
                    )
                );
            }
        }
        return List.copyOf(result);
    }

    public static double cosine(
        float[] left,
        float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length)
            throw new IllegalArgumentException("Incompatible evidence vectors");
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            if (!Float.isFinite(left[i]) || !Float.isFinite(right[i]))
                throw new IllegalArgumentException("Invalid evidence vector");
            dot += (double) left[i] * right[i];
            a += (double) left[i] * left[i];
            b += (double) right[i] * right[i];
        }
        if (a == 0 || b == 0)
            throw new IllegalArgumentException("Empty evidence vector");
        return Math.max(-1, Math.min(1, dot / Math.sqrt(a * b)));
    }

    /** Exact declarations settle only simple named-skill clauses, never experience or proficiency. */
    public static Optional<Map<String, Object>> declaredSkillVerdict(
        Requirement requirement,
        List<Chunk> chunks) {
        if (requirement.skills().isEmpty() || !Set.of("AND", "OR").contains(requirement.relation()))
            return Optional.empty();
        String remaining = requirement.text();
        var mentions = new ArrayList<>(JobRequirementExtractor.mentions(remaining));
        mentions.sort(Comparator.comparingInt(JobRequirementExtractor.SkillMention::start).reversed());
        for (var mention : mentions)
            remaining = remaining.substring(0, mention.start()) + " " + remaining.substring(mention.end());
        remaining = JobRequirementExtractor.fold(remaining).replaceAll("[^a-z0-9 ]", " ")
            .replaceAll(
                ("\\b(knowledge|of|skills|skill|required|preferred|and|or|with|in|basic|"
                    + "biet|kien thuc|ve|ky nang|va|hoac|co ban|uu tien|yeu cau)\\b"),
                " "
            ).strip();
        if (!remaining.isBlank())
            return Optional.empty();
        Map<String, Chunk> declared = new HashMap<>();
        for (Chunk chunk : chunks) {
            if (!chunk.source().contains("/skills/"))
                continue;
            String first = chunk.text().lines().findFirst().orElse("");
            String name = first.startsWith("name: ") ? first.substring(6) : first;
            declared.put(SkillCatalog.get().comparisonKey(name), chunk);
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        int matched = 0;
        for (String skill : requirement.skills()) {
            Chunk chunk = declared.get(SkillCatalog.get().comparisonKey(skill));
            if (chunk != null) {
                matched++;
                evidence.add(fields("chunk_id", chunk.id(), "source", chunk.source(), "quote", chunk.text()));
            }
        }
        boolean met = requirement.relation().equals("OR") ? matched > 0 : matched == requirement.skills().size();
        if (!met)
            return Optional.empty();
        return Optional.of(
            fields(
                "status",
                "MET",
                "evidence",
                evidence,
                "reason",
                ("CV/hồ sơ có khai báo đúng kỹ năng được yêu cầu; chưa xác minh mức " + "thành thạo thực tế.")
            )
        );
    }

    public static List<Map<String, Object>> retrieve(
        Requirement requirement,
        float[] query,
        List<Chunk> chunks,
        List<float[]> vectors) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            Set<String> names = new HashSet<>();
            JobRequirementExtractor.recognize(chunk.text())
                .forEach(skill -> names.add(SkillCatalog.get().comparisonKey(skill.name())));
            boolean exact =
                requirement.skills().stream().anyMatch(name -> names.contains(SkillCatalog.get().comparisonKey(name)));
            candidates.add(
                fields(
                    "chunk_id",
                    chunk.id(),
                    "source",
                    chunk.source(),
                    "text",
                    chunk.text(),
                    "similarity",
                    cosine(query, vectors.get(i)),
                    "exact_skill",
                    exact
                )
            );
        }
        candidates.sort(
            Comparator.<Map<String, Object>>comparingDouble(row -> number(row.get("similarity"), -1)).reversed()
                .thenComparing(row -> text(row.get("chunk_id")))
        );
        LinkedHashMap<String, Map<String, Object>> selected = new LinkedHashMap<>();
        candidates.stream().limit(3).forEach(row -> selected.put(text(row.get("chunk_id")), row));
        candidates.stream().filter(row -> Boolean.TRUE.equals(row.get("exact_skill"))).limit(3)
            .forEach(row -> selected.put(text(row.get("chunk_id")), row));
        return List.copyOf(selected.values());
    }

    public static Map<String, Map<String, Object>> validate(
        List<Map<String, Object>> supplied,
        Map<String, Object> generated) {
        if (!(generated.get("results") instanceof List<?> rows))
            throw invalid();
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        supplied.forEach(row -> expected.put(text(row.get("requirement_id")), row));
        Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        for (Object raw : rows) {
            var row = object(raw);
            String id = text(row.get("requirement_id"));
            var input = expected.get(id);
            String status = text(row.get("status"));
            if (input == null || accepted.containsKey(id)
                || !Set.of("MET", "PARTIAL", "NOT_MET", "MISSING_EVIDENCE", "UNVERIFIED").contains(status)
                || !(row.get("reason") instanceof String reason) || reason.isBlank() || reason.length() > 2000
                || !(row.get("evidence") instanceof List<?> evidence) || evidence.size() > 6)
                throw invalid();
            Map<String, Map<String, Object>> allowed = new HashMap<>();
            array(input.get("candidates")).forEach(value -> {
                var chunk = object(value);
                allowed.put(text(chunk.get("chunk_id")), chunk);
            });
            List<Map<String, Object>> verified = new ArrayList<>();
            Set<String> cited = new HashSet<>();
            for (Object value : evidence) {
                var citation = object(value);
                String chunkId = text(citation.get("chunk_id"));
                String quote = text(citation.get("quote"));
                var chunk = allowed.get(chunkId);
                if (chunk == null || quote.isBlank() || quote.length() > 1400
                    || !text(chunk.get("text")).contains(quote) || !cited.add(chunkId + ":" + quote))
                    throw invalid();
                verified.add(
                    fields(
                        "chunk_id",
                        chunkId,
                        "source",
                        chunk.get("source"),
                        "quote",
                        quote,
                        "similarity",
                        chunk.get("similarity")
                    )
                );
            }
            if (Set.of("MET", "PARTIAL", "NOT_MET").contains(status) && verified.isEmpty())
                throw invalid();
            if (Set.of("MET", "PARTIAL").contains(status) && !supportsNamedTechnologies(input, verified, status)) {
                status = "UNVERIFIED";
                reason = ("Trích dẫn chưa thể hiện đúng công nghệ được JD yêu cầu; công nghệ "
                    + "liên quan không tự thay thế nhau.");
            }
            accepted.put(id, fields("status", status, "reason", reason, "evidence", verified));
        }
        if (!accepted.keySet().equals(expected.keySet()))
            throw invalid();
        return accepted;
    }

    private static boolean supportsNamedTechnologies(
        Map<String, Object> requirement,
        List<Map<String, Object>> evidence,
        String status) {
        Set<String> required = new HashSet<>(), documented = new HashSet<>();
        for (Object name : array(requirement.get("skills")))
            JobRequirementExtractor.recognize(text(name)).stream().filter(skill -> skill.type().equals("TECHNOLOGY"))
                .forEach(skill -> required.add(SkillCatalog.get().comparisonKey(skill.name())));
        if (required.isEmpty())
            return true;
        for (var citation : evidence)
            JobRequirementExtractor.recognize(text(citation.get("quote")))
                .forEach(skill -> documented.add(SkillCatalog.get().comparisonKey(skill.name())));
        if (status.equals("PARTIAL") || "OR".equals(requirement.get("relation")))
            return required.stream().anyMatch(documented::contains);
        return documented.containsAll(required);
    }

    public static Map<String, Object> report(
        List<Requirement> requirements,
        Map<String, Map<String, Object>> verdicts,
        String extractionStatus) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> strengths = new ArrayList<>(), gaps = new ArrayList<>();
        int required = 0, optional = 0, unknown = 0;
        double earned = 0, preferredEarned = 0;
        for (Requirement requirement : requirements) {
            var verdict = verdicts.get(requirement.id());
            if (verdict == null)
                throw invalid();
            String status = text(verdict.get("status"));
            double credit = status.equals("MET") ? 1 : status.equals("PARTIAL") ? 0.5 : 0;
            boolean mandatory = requirement.importance().equals("MANDATORY");
            boolean preferred = requirement.importance().equals("PREFERRED");
            if (mandatory) {
                required++;
                earned += credit;
            } else if (preferred) {
                optional++;
                preferredEarned += credit;
            }
            if (status.equals("UNVERIFIED") || Set.of("UNSPECIFIED", "CONDITIONAL").contains(requirement.importance()))
                unknown++;
            var item = new LinkedHashMap<>(verdict);
            item.putAll(
                fields(
                    "requirement_id",
                    requirement.id(),
                    "text",
                    requirement.text(),
                    "importance",
                    requirement.importance(),
                    "relation",
                    requirement.relation(),
                    "skills",
                    requirement.skills(),
                    "credit",
                    credit
                )
            );
            rows.add(item);
            if (status.equals("MET"))
                strengths.add(requirement.text());
            else if ((mandatory || preferred) && !status.equals("UNVERIFIED"))
                gaps.add(requirement.text());
        }
        double score = required == 0 ? 0 : earned / required;
        boolean unclearScope = requirements.stream().anyMatch(
            row -> Set.of("UNSPECIFIED", "CONDITIONAL").contains(row.importance())
                || !Set.of("AND", "OR").contains(row.relation())
        );
        boolean available =
            required > 0 && !unclearScope && !Set.of("TRUNCATED", "MISSING_SOURCE").contains(extractionStatus);
        return fields(
            "version",
            VERSION,
            "method",
            "REQUIREMENT_EVIDENCE",
            "score",
            score,
            "score_kind",
            "REQUIREMENT_EVIDENCE_COVERAGE",
            "requirement_coverage",
            available ? score : null,
            "requirement_coverage_available",
            available,
            "skill_coverage",
            score,
            "preferred_coverage",
            optional == 0 ? null : preferredEarned / optional,
            "required_count",
            required,
            "preferred_count",
            optional,
            "earned_required",
            earned,
            "requirements",
            rows,
            "strengths",
            strengths,
            "skill_gaps",
            gaps,
            "unverified_requirements",
            unknown,
            "extraction_status",
            extractionStatus,
            "coverage_complete",
            available && unknown == 0,
            "catalog_version",
            SkillCatalog.get().version(),
            "reason",
            "Đối chiếu bằng chứng cho " + required + " yêu cầu bắt buộc; " + unknown
                + (" yêu cầu cần làm rõ. Điểm phản ánh thông tin trong CV, không xác minh " + "năng lực thực tế.")
        );
    }

    private static ApiException invalid() {
        return new ApiException(502, "AI requirement evidence is incomplete or contains unsupported citations");
    }
}
