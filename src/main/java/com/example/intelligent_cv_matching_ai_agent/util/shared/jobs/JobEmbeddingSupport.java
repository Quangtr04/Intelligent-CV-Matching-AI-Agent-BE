package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

public final class JobEmbeddingSupport {

    private JobEmbeddingSupport() {
    }

    public static String embeddingText(
        Map<String, Object> job) {
        return String.join(
            "\n",
            text(job.get("job_title")),
            text(job.get("raw_content")),
            text(job.get("parsed_requirements")),
            text(job.get("parsed_responsibilities")),
            text(job.get("employment_type"))
        );
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
