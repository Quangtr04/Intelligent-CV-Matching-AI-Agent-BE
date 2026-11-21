package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeSet;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

public final class JobEmbeddingSupport {

    public static final String VERSION = "job-search-v2";

    private JobEmbeddingSupport() {
    }

    public static String embeddingText(
        Map<String, Object> job) {
        StringJoiner result = new StringJoiner("\n");
        result.add("Job title: " + clean(text(job.get("job_title"))));
        result.add("Role: " + clean(text(job.get("role_category"))));
        result.add("Seniority: " + clean(text(job.get("parsed_seniority"))));
        result.add("Employment type: " + clean(text(job.get("employment_type"))));
        String description = text(job.get("parsed_description"));
        // Imported descriptions can include the benefits/company footer. Keep only the job-facing section.
        description = description.split(
            ("(?im)^\\s*(?:benefits|what we offer|about (?:us|the company)|quyền "
                + "lợi|phúc lợi|về công ty)\\s*[:\\n]"),
            2
        )[0];
        if (!description.isBlank())
            result.add("Job description: " + clean(description));
        var extraction = JobRequirementExtractor.forJob(job);
        var seen = new TreeSet<String>();
        for (var requirement : extraction.requirements()) {
            String clause =
                requirement.importance() + " [" + requirement.relation() + "]: " + clean(requirement.text());
            if (seen.add(clause))
                result.add(clause);
        }
        if (!text(job.get("parsed_responsibilities")).isBlank())
            result.add("Responsibilities: " + clean(text(job.get("parsed_responsibilities"))));
        var skills = new TreeSet<String>();
        for (Object raw : array(job.get("skills"))) {
            var row = object(raw);
            String name = text(row.getOrDefault("name", row.get("skill_name")));
            if (!name.isBlank())
                skills.add(
                    (Boolean.FALSE.equals(row.getOrDefault("required", row.get("is_required")))
                        ? "Preferred skill: "
                        : "Required skill: ") + SkillCatalog.get().canonicalName(name)
                );
        }
        skills.forEach(result::add);
        return result.toString();
    }

    public static String inputHash(
        Map<String, Object> job) {
        return FlowSupport.hash(
            VERSION + "\n" + JobRequirementExtractor.VERSION + "\n" + SkillCatalog.get().version() + "\n"
                + embeddingText(job)
        );
    }

    private static String clean(
        String value) {
        return org.jsoup.Jsoup.parse(value).text().replaceAll("https?://\\S+", "").strip();
    }

    public static boolean usable(
        Map<String, Object> job,
        String hash) {
        if (!"ACTIVE".equals(job.get("status")) || !Objects.equals(hash, job.get("raw_content_hash")))
            return false;

        Object expiry = job.get("valid_through");
        if (expiry == null)
            return true;
        Instant deadline = expiry instanceof Instant instant
            ? instant
            : expiry instanceof OffsetDateTime date
                ? date.toInstant()
                : expiry instanceof Timestamp timestamp
                    ? timestamp.toInstant()
                    : OffsetDateTime.parse(expiry.toString()).toInstant();

        return deadline.isAfter(Instant.now());
    }

    public static String safeError(
        RuntimeException ex) {
        String message = ex instanceof ApiException ? ex.getMessage() : "Embedding provider or publication failed";
        if (message == null)
            return "Embedding failed";

        return message.substring(0, Math.min(message.length(), 1000));
    }
}
