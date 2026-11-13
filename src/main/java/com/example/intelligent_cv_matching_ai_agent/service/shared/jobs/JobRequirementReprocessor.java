package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/**
 * Explicit maintenance operation; bounded batches, no fetches, manual skills/reviewed jobs and history remain
 * protected.
 */
@Service
public class JobRequirementReprocessor {

    private final SqlStore db;

    public JobRequirementReprocessor(
        SqlStore db) {
        this.db = db;
    }

    @Transactional
    public int reprocessBatch(
        int limit) {
        if (limit < 1 || limit > 500)
            throw new IllegalArgumentException("Batch size must be 1..500");
        var jobs = db.list(
            ("SELECT id,ingestion_method,reviewed_by_user_id,parsed_requirements,"
                + "parsed_responsibilities,original_content,raw_content FROM "
                + "market_job_posts WHERE NOT requirement_extraction_verified AND "
                + "ingestion_method IN ('CRAWL','SCHEDULED_CRAWL') AND "
                + "reviewed_by_user_id IS NULL AND (requirement_extraction->>'version' "
                + "IS DISTINCT FROM ? OR requirement_extraction->>'catalogVersion' IS "
                + "DISTINCT FROM ?) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"),
            JobRequirementExtractor.VERSION,
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get().version(),
            limit
        );
        java.util.Map<String, Object> catalogIds = new java.util.HashMap<>();
        if (jobs.stream().anyMatch(
            job -> java.util.Set.of("CRAWL", "SCHEDULED_CRAWL").contains(text(job.get("ingestion_method")))
                && job.get("reviewed_by_user_id") == null
        )) {
            var catalog = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get();
            for (var skill : db.list("SELECT id,name FROM skills ORDER BY lower(name),id")) {
                String name = text(skill.get("name"));
                String key = catalog.comparisonKey(name);
                if (!catalogIds.containsKey(key) || name.equalsIgnoreCase(catalog.canonicalName(name))) {
                    catalogIds.put(key, skill.get("id"));
                }
            }
        }
        for (var job : jobs) {
            String source = text(job.get("original_content"));
            if (source.isBlank())
                source = text(job.get("raw_content"));
            var extraction = JobRequirementExtractor.extract(
                "",
                text(job.get("parsed_requirements")),
                "<section>" + source + "</section><h2>Responsibilities</h2>" + text(job.get("parsed_responsibilities"))
            );
            db.update(
                ("UPDATE market_job_posts SET requirement_extraction=? WHERE id=? AND "
                    + "NOT requirement_extraction_verified"),
                db.json(extraction),
                job.get("id")
            );
            if (java.util.Set.of("CRAWL", "SCHEDULED_CRAWL").contains(text(job.get("ingestion_method")))
                && job.get("reviewed_by_user_id") == null) {
                db.update("DELETE FROM market_job_skills WHERE market_job_id=?", job.get("id"));
                for (var skill : extraction.legacySkills()) {
                    String key = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
                        .comparisonKey(skill.name());
                    db.update(
                        ("INSERT INTO market_job_skills(market_job_id,skill_id,skill_name,"
                            + "normalized_skill,is_required) VALUES(?,?,?,?,?)"),
                        job.get("id"),
                        catalogIds.get(key),
                        skill.name(),
                        JobRequirementExtractor.fold(skill.name()),
                        skill.required()
                    );
                }
            }
        }
        return jobs.size();
    }
}
