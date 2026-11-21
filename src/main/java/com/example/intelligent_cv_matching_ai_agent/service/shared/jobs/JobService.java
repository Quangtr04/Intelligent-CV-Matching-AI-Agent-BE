package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSalaryParser;
import java.time.OffsetDateTime;
import java.util.*;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class JobService {

    private final JobRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final JobEmbeddingService embeddings;

    private final SalaryExchangeRateService rates;

    @Autowired
    public JobService(
        JobRepository db,
        CurrentUser user,
        AiService ai,
        JobEmbeddingService embeddings,
        SalaryExchangeRateService rates) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.embeddings = embeddings;
        this.rates = rates;
    }

    public JobService(
        JobRepository db,
        CurrentUser user,
        AiService ai) {
        this(db, user, ai, null, null);
    }

    public List<Map<String, Object>> skills() {
        return db.listSkillsByIsActive();
    }

    public List<Map<String, Object>> jobs(
        String q,
        String location,
        int limit,
        int offset) {
        String query = text(q);
        String requestedLocation = text(location);
        if (query.length() > 255 || requestedLocation.length() > 255)
            throw ApiException.badRequest("Search value too long");
        String normalizedLocation = JobNormalizer.fold(JobNormalizer.normalizeLocationFilter(requestedLocation));
        String locationPattern =
            "%" + normalizedLocation.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String titlePattern = "%" + query + "%";
        int size = pageSize(limit);
        int start = offset(offset);
        var rows = user.hasOperationsRole()
            ? db.listMarketJobPostsWithCreatorByStatus(titlePattern, locationPattern, size, start)
            : db.listMarketJobPostsByStatus(titlePattern, locationPattern, size, start);
        return rows.stream().map(this::withMonthlySalary).toList();
    }

    private Map<String, Object> withMonthlySalary(
        Map<String, Object> job) {
        var parsed = JobSalaryParser.parseMonthly(text(job.get("salary_text")));
        if (parsed.isEmpty())
            return job;
        var salary = parsed.get();
        var result = new LinkedHashMap<>(job);
        if ("VND".equals(salary.currency())) {
            result.put("monthly_salary_min_vnd", salary.minimum());
            result.put("monthly_salary_max_vnd", salary.maximum());
        } else if (rates != null) {
            var exchange = rates.snapshot();
            if (exchange.rate(salary.currency(), "VND").isPresent()) {
                if (salary.minimum() != null)
                    result.put("monthly_salary_min_vnd", exchange.convert(salary.minimum(), salary.currency(), "VND"));
                if (salary.maximum() != null)
                    result.put("monthly_salary_max_vnd", exchange.convert(salary.maximum(), salary.currency(), "VND"));
            }
        }
        return result;
    }

    public List<Map<String, Object>> managedJobs(
        String q,
        String status,
        UUID createdBy,
        String ingestionMethod,
        int limit,
        int offset) {
        user.requireOperationsRole();
        String query = text(q);
        if (query.length() > 255)
            throw ApiException.badRequest("Search value too long");
        String normalizedStatus = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null
            && !Set.of("PENDING", "ACTIVE", "REJECTED", "EXPIRED", "REMOVED", "INVALID").contains(normalizedStatus))
            throw ApiException.badRequest("Invalid job status filter");
        String normalizedMethod = ingestionMethod == null || ingestionMethod.isBlank()
            ? null
            : ingestionMethod.strip().toUpperCase(Locale.ROOT);
        if (normalizedMethod != null && !Set.of("MANUAL", "CRAWL", "SCHEDULED_CRAWL").contains(normalizedMethod))
            throw ApiException.badRequest("Invalid ingestion method filter");
        return db.listManagedJobs(
            "%" + query + "%",
            normalizedStatus,
            createdBy,
            normalizedMethod,
            pageSize(limit),
            offset(offset)
        );
    }

    public Map<String, Object> job(
        UUID id) {
        var job = user.hasOperationsRole() ? db.getMarketJobPostsWithCreatorById(id) : db.getMarketJobPostsById(id);
        job.put("skills", db.listMarketJobSkillsByMarketJobId(id));
        job.put("sources", db.sources(id));
        return job;
    }

    private Map<String, Object> jobWithCreator(
        UUID id) {
        var job = db.getMarketJobPostsWithCreatorById(id);
        job.put("skills", db.listMarketJobSkillsByMarketJobId(id));
        job.put("sources", db.sources(id));
        return job;
    }

    @Transactional
    public Map<String, Object> importJob(
        ImportJobRequest input) {
        user.requireOperationsRole();
        return importPrepared(input, user.id(), "MANUAL");
    }

    /** Internal scheduler entry point, authorized against the persisted administrator. */
    @Transactional
    public Map<String, Object> importJobAs(
        ImportJobRequest input,
        UUID actor) {
        db.requireActiveAdmin(actor);
        return importPrepared(input, actor, "SCHEDULED_CRAWL");
    }

    private Map<String, Object> importPrepared(
        ImportJobRequest raw,
        UUID actor) {
        return importPrepared(raw, actor, "MANUAL");
    }

    private Map<String, Object> importPrepared(
        ImportJobRequest raw,
        UUID actor,
        String ingestionMethod) {
        ImportJobRequest input = JobNormalizer.normalize(raw);
        if (input.validThrough() != null && !input.validThrough().isAfter(OffsetDateTime.now()))
            throw ApiException.badRequest("Cannot import an expired job");
        String normalized = Jsoup.parse(input.content()).text().strip();
        if (normalized.length() < 30)
            throw ApiException.badRequest("Job description must contain at least 30 characters");
        String hash = JobNormalizer.contentHash(input);
        String fingerprint = JobNormalizer.fingerprint(input);
        // A consistent lock order serializes concurrent imports across sources.
        if (fingerprint != null)
            db.readDatabaseValue("FINGERPRINT:" + fingerprint);
        db.readDatabaseValue(input.sourceUrl());
        var previousSource = db.source(input.sourceUrl());
        var same = db.findActiveSource(input.sourceUrl(), hash);
        if (same.isEmpty())
            same = db.findMarketJobPostsByRawContentHash(hash);
        if (same.isPresent()) {
            String existingStatus = text(same.get().get("status"));
            if (!"ACTIVE".equals(existingStatus) && !"PENDING".equals(existingStatus))
                throw ApiException.conflict("Previously imported job is inactive; change its status explicitly");
            UUID id = uuid(same.get().get("id"));
            lockSourceJobs(previousSource, id);
            db.saveSource(id, input.sourceSite(), input.sourceUrl(), hash, input.validThrough());
            retirePrevious(previousSource, id);
            if ("ACTIVE".equals(existingStatus))
                enqueue(id, text(same.get().get("raw_content_hash")), actor);
            var result = jobWithCreator(id);
            result.put("deduplicated", true);
            return result;
        }
        var crossSource = db.findCrossSourceDuplicate(fingerprint, input.sourceSite());
        if (crossSource.isPresent()) {
            UUID id = uuid(crossSource.get().get("id"));
            lockSourceJobs(previousSource, id);
            db.saveSource(id, input.sourceSite(), input.sourceUrl(), hash, input.validThrough());
            retirePrevious(previousSource, id);
            if ("ACTIVE".equals(crossSource.get().get("status")))
                enqueue(id, text(crossSource.get().get("raw_content_hash")), actor);
            db.insertAuditLogs(
                fields(
                    "actor_user_id",
                    actor,
                    "action",
                    "JOB_SOURCE_LINK",
                    "entity_type",
                    "market_job_posts",
                    "entity_id",
                    id
                )
            );
            var result = jobWithCreator(id);
            result.put("deduplicated", true);
            return result;
        }
        Map<String, Object> postFields = fields(
            "source_site",
            input.sourceSite(),
            "source_url",
            input.sourceUrl(),
            "raw_content_hash",
            hash,
            "content_fingerprint",
            fingerprint,
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
            "employment_type",
            input.employmentType(),
            "valid_through",
            input.validThrough(),
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
            JobNormalizer.lines(input.content()),
            "parsed_requirements",
            input.requirements(),
            "parsed_responsibilities",
            input.responsibilities(),
            "parsed_benefits",
            input.benefits(),
            "parsed_at",
            OffsetDateTime.now(),
            "posted_at",
            input.postedAt(),
            "collected_at",
            OffsetDateTime.now(),
            "status",
            "ACTIVE",
            "created_by_user_id",
            actor,
            "ingestion_method",
            ingestionMethod
        );
        postFields.put(
            "requirement_extraction",
            db.json(
                com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.extract(
                    "",
                    input.requirements(),
                    "<section>" + input.content() + "</section><h2>Responsibilities</h2>"
                        + (input.responsibilities() == null ? "" : input.responsibilities())
                )
            )
        );
        UUID id = db.insertMarketJobPosts(postFields);
        for (JobSkillRequest skill : input.skills() == null ? List.<JobSkillRequest>of() : input.skills()) {
            UUID skillId = skill.skillId();
            if (skillId != null)
                db.getSkillsById(skillId);
            else
                skillId = db.findSkillByName(skill.name()).map(row -> uuid(row.get("id"))).orElse(null);
            db.updateMarketJobSkills(
                id,
                skillId,
                skill.name(),
                skill.name().toLowerCase(Locale.ROOT),
                skill.required()
            );
        }
        lockSourceJobs(previousSource, id);
        db.saveSource(id, input.sourceSite(), input.sourceUrl(), hash, input.validThrough());
        retirePrevious(previousSource, id);
        db.updateMarketJobEmbeddingsByMarketJobId(input.sourceUrl(), id);
        db.updateMarketJobPostsBySourceUrl(input.sourceUrl(), id);
        enqueue(id, hash, actor);
        db.insertAuditLogs(
            fields("actor_user_id", actor, "action", "JOB_IMPORT", "entity_type", "market_job_posts", "entity_id", id)
        );
        var result = jobWithCreator(id);
        result.put("deduplicated", false);
        return result;
    }

    private void retirePrevious(
        Optional<Map<String, Object>> previous,
        UUID current) {
        previous.map(row -> uuid(row.get("market_job_id"))).filter(id -> !id.equals(current)).ifPresent(id -> {
            db.retireOrphan(id);
            db.refreshDeadline(id);
        });
    }

    private void lockSourceJobs(
        Optional<Map<String, Object>> previous,
        UUID target) {
        var ids = new ArrayList<UUID>();
        ids.add(target);
        previous.map(row -> uuid(row.get("market_job_id"))).filter(id -> !id.equals(target)).ifPresent(ids::add);
        db.lockJobs(ids);
    }

    private void enqueue(
        UUID id,
        String hash,
        UUID actor) {
        if (embeddings != null)
            embeddings.enqueue(id, hash, actor);
    }

    public Map<String, Object> index(
        UUID id) {
        user.requireOperationsRole();
        if (embeddings != null)
            return embeddings.indexNow(id, user.id());
        var job = job(id);
        float[] embedding = ai.embed(JobEmbeddingSupport.embeddingText(job));
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

    public static String embeddingText(
        Map<String, Object> job) {
        return JobEmbeddingSupport.embeddingText(job);
    }

    public Map<String, Object> indexStatus(
        UUID id) {
        user.requireOperationsRole();
        db.getMarketJobPostsById2(id);
        return Map.of("job_id", id, "indexing_jobs", db.indexingJobs(id));
    }

    public Map<String, Object> backfillEmbeddings(
        int limit) {
        user.requireOperationsRole();
        if (embeddings == null)
            throw new ApiException(503, "Job embedding worker is unavailable");
        return embeddings.backfill(user.id(), limit);
    }

    public Map<String, Object> embeddingCoverage() {
        user.requireOperationsRole();
        if (embeddings == null)
            throw new ApiException(503, "Job embedding worker is unavailable");
        return embeddings.coverage();
    }

    @Transactional
    public Map<String, Object> status(
        UUID id,
        Map<String, Object> input) {
        user.requireOperationsRole();
        String status = required(input, "status", 20);
        if (!Set.of("PENDING", "ACTIVE", "REJECTED", "EXPIRED", "REMOVED", "INVALID").contains(status))
            throw ApiException.badRequest("Invalid job status");
        String rawNote = text(input.get("reviewNote")).strip();
        if (rawNote.length() > 2000)
            throw ApiException.badRequest("reviewNote maximum 2000 characters");
        String reviewNote = rawNote.isEmpty() ? null : rawNote;
        var existing = db.getMarketJobPostsById3(id);
        Object deadline = existing.get("valid_through");
        java.time.Instant expiry = deadline == null
            ? null
            : deadline instanceof OffsetDateTime date
                ? date.toInstant()
                : deadline instanceof java.sql.Timestamp stamp
                    ? stamp.toInstant()
                    : deadline instanceof java.time.Instant instant
                        ? instant
                        : OffsetDateTime.parse(deadline.toString()).toInstant();
        if ("ACTIVE".equals(status) && expiry != null && !expiry.isAfter(java.time.Instant.now()))
            throw ApiException.badRequest("Job deadline has elapsed; import a new source version");
        db.getMarketJobPostsById2(id);
        db.updateMarketJobReviewById(status, user.id(), reviewNote, id);
        db.updateSourceStatus(id, status);
        if (!status.equals("ACTIVE"))
            db.updateMarketJobEmbeddingsByMarketJobId2(id);
        else
            enqueue(id, text(existing.get("raw_content_hash")), user.id());
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
        return jobWithCreator(id);
    }

    static String contentHash(
        String sourceSite,
        String sourceUrl,
        ImportJobRequest input,
        String normalizedContent) {
        return JobNormalizer.contentHash(JobNormalizer.normalize(input));
    }
}
