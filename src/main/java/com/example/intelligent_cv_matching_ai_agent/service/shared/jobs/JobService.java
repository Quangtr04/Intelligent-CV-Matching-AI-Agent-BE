package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import org.jsoup.Jsoup;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class JobService {

    private final JobRepository db;

    private final CurrentUser user;

    private final AiService ai;

    public JobService(
        JobRepository db,
        CurrentUser user,
        AiService ai) {
        this.db = db;
        this.user = user;
        this.ai = ai;
    }

    public List<Map<String, Object>> skills() {
        return db.listSkillsByIsActive();
    }

    public List<Map<String, Object>> jobs(
        String q,
        String location,
        int limit,
        int offset) {
        if (q.length() > 255 || location.length() > 255)
            throw ApiException.badRequest("Search value too long");

        return db.listMarketJobPostsByStatus("%" + q + "%", "%" + location + "%", pageSize(limit), offset(offset));
    }

    public Map<String, Object> job(
        UUID id) {
        var job = db.getMarketJobPostsById(id);
        job.put("skills", db.listMarketJobSkillsByMarketJobId(id));

        return job;
    }

    @Transactional
    public Map<String, Object> importJob(
        ImportJobRequest input) {
        user.requireRole("ADMIN");

        String sourceSite = input.sourceSite().strip().toUpperCase(Locale.ROOT);
        String sourceUrl = JobSourceValidator.validateSourceUrl(sourceSite, input.sourceUrl()).toASCIIString();
        db.readDatabaseValue(sourceUrl);
        String normalized = Jsoup.parse(input.content()).text().strip();
        if (normalized.length() < 30)
            throw ApiException.badRequest("Job description must contain at least 30 characters");

        String hash = FlowSupport.hash(sourceSite + "\n" + sourceUrl + "\n" + input.jobTitle() + "\n" + normalized);
        var same = db.findMarketJobPostsByRawContentHash(hash);

        if (same.isPresent()) {
            if (!"ACTIVE".equals(same.get().get("status")))
                throw ApiException.conflict("Previously imported job is inactive; change its status explicitly");

            return same.get();
        }

        UUID id = db.insertMarketJobPosts(
            fields(
                "source_site",
                sourceSite,
                "source_url",
                sourceUrl,
                "raw_content_hash",
                hash,
                "job_title",
                input.jobTitle(),
                "company_name",
                input.companyName(),
                "location",
                input.location(),
                "salary_text",
                input.salaryText(),
                "role_category",
                input.roleCategory(),
                "raw_content",
                normalized,
                "original_content",
                input.content(),
                "original_content_format",
                "html",
                "original_content_quality",
                "provided",
                "original_content_extracted_at",
                OffsetDateTime.now(),
                "parsed_description",
                normalized,
                "collected_at",
                OffsetDateTime.now(),
                "status",
                "ACTIVE"
            )
        );

        for (JobSkillRequest skill : input.skills() == null ? List.<JobSkillRequest>of() : input.skills()) {
            if (skill.skillId() != null)
                db.getSkillsById(skill.skillId());

            db.updateMarketJobSkills(
                id,
                skill.skillId(),
                skill.name(),
                skill.name().strip().toLowerCase(Locale.ROOT),
                skill.required()
            );
        }

        db.updateMarketJobEmbeddingsByMarketJobId(sourceUrl, id);
        db.updateMarketJobPostsBySourceUrl(sourceUrl, id);
        db.insertAuditLogs(
            fields(
                "actor_user_id",
                user.id(),
                "action",
                "JOB_IMPORT",
                "entity_type",
                "market_job_posts",
                "entity_id",
                id
            )
        );

        return job(id);
    }

    @Transactional
    public Map<String, Object> index(
        UUID id) {
        user.requireRole("ADMIN");

        var job = job(id);
        float[] embedding = ai.embed(text(job.get("job_title")) + "\n" + text(job.get("raw_content")));

        db.updateMarketJobEmbeddingsByMarketJobId2(id);
        db.updateMarketJobEmbeddings(
            id,
            ai.provider(),
            ai.embeddingModel(),
            FlowSupport.vector(embedding),
            job.get("raw_content_hash")
        );
        UUID indexing = db.insertVectorIndexingJobs(
            fields(
                "entity_type",
                "JOB",
                "entity_id",
                id,
                "operation",
                "INDEX",
                "provider",
                ai.provider(),
                "model",
                ai.embeddingModel(),
                "status",
                "COMPLETED",
                "attempt_count",
                1,
                "started_at",
                OffsetDateTime.now(),
                "completed_at",
                OffsetDateTime.now()
            )
        );

        return Map.of("id", indexing, "status", "COMPLETED");
    }

    @Transactional
    public Map<String, Object> status(
        UUID id,
        Map<String, Object> input) {
        user.requireRole("ADMIN");

        String status = required(input, "status", 20);
        if (!Set.of("ACTIVE", "EXPIRED", "REMOVED", "INVALID").contains(status))
            throw ApiException.badRequest("Invalid job status");

        db.getMarketJobPostsById2(id);

        db.updateMarketJobPostsById(status, id);

        if (!status.equals("ACTIVE"))
            db.updateMarketJobEmbeddingsByMarketJobId2(id);

        db.insertAuditLogs(
            fields(
                "actor_user_id",
                user.id(),
                "action",
                "JOB_STATUS",
                "entity_type",
                "market_job_posts",
                "entity_id",
                id,
                "new_value",
                db.json(input)
            )
        );

        return db.getMarketJobPostsById3(id);
    }
}
