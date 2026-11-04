package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceProjection;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by JobService. */
@Repository("marketJobRepository")
public class JobRepository {

    // Match Vietnamese place names with or without accents, without requiring a database extension.
    private static final String LOCATION_SEARCH_SQL = "lower(translate(regexp_replace("
        + "normalize(COALESCE(j.location,''), NFD), U&'[\\0300-\\036f]', '', 'g'), 'đĐ', 'dd'))";

    private static final String PUBLIC_JOB_SUMMARY_COLUMNS =
        "j.id,j.job_title,j.company_name,j.location,j.salary_text,j.role_category,"
            + "j.posted_at,j.valid_through,j.employment_type,j.source_url AS original_source_url,"
            + "j.source_site AS original_source_site," + JobSourceProjection.ACTIVE_URL + " AS source_url,"
            + JobSourceProjection.ACTIVE_SITE + " AS source_site";

    private static final String STAFF_JOB_CREATOR_COLUMNS =
        "j.created_by_user_id,creator.full_name AS created_by_full_name,creator.email AS created_by_email,"
            + "creator.role AS created_by_role,mp.sub_role AS created_by_sub_role,"
            + "j.ingestion_method,j.task_id,task.title AS task_title,"
            + "j.reviewed_by_user_id,reviewer.full_name AS reviewed_by_full_name,"
            + "reviewer.email AS reviewed_by_email,j.reviewed_at,j.review_note";

    private static final String STAFF_JOB_JOINS = " LEFT JOIN users creator ON creator.id=j.created_by_user_id"
        + " LEFT JOIN management_profiles mp ON mp.user_id=j.created_by_user_id"
        + " LEFT JOIN management_tasks task ON task.id=j.task_id"
        + " LEFT JOIN users reviewer ON reviewer.id=j.reviewed_by_user_id ";

    private final SqlStore db;

    public JobRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listSkillsByIsActive(
        Object... args) {
        return db.list(
            ("SELECT id,name,slug,category,description,difficulty_level FROM skills "
                + "WHERE is_active ORDER BY name LIMIT 1000"),
            args
        );
    }

    public List<Map<String, Object>> listMarketJobPostsByStatus(
        Object... args) {
        return db.list(
            "SELECT " + PUBLIC_JOB_SUMMARY_COLUMNS + " FROM market_job_posts j WHERE "
                + "j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now()) "
                + "AND j.job_title ILIKE ? AND " + LOCATION_SEARCH_SQL
                + " ILIKE ? ESCAPE '!' ORDER BY j.collected_at DESC,j.id LIMIT ? OFFSET ?",
            args
        );
    }

    public List<Map<String, Object>> listMarketJobPostsWithCreatorByStatus(
        Object... args) {
        return db.list(
            "SELECT " + PUBLIC_JOB_SUMMARY_COLUMNS + "," + STAFF_JOB_CREATOR_COLUMNS + " FROM market_job_posts j"
                + STAFF_JOB_JOINS + "WHERE "
                + "j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now()) "
                + "AND j.job_title ILIKE ? AND " + LOCATION_SEARCH_SQL
                + " ILIKE ? ESCAPE '!' ORDER BY j.collected_at DESC,j.id LIMIT ? OFFSET ?",
            args
        );
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one(
            "SELECT j.id,j.source_site,j.source_url,j.raw_content_hash,j.content_fingerprint,"
                + "j.job_title,j.company_name,j.location,j.salary_text,j.role_category,j.employment_type,"
                + "j.raw_content,j.original_content,j.original_content_format,j.original_content_quality,"
                + "j.original_content_extracted_at,j.parsed_description,j.parsed_requirements,"
                + "j.parsed_responsibilities,j.parsed_benefits,j.parsed_company_overview,j.parsed_seniority,"
                + "j.parsed_confidence,j.parsed_at,j.posted_at,j.valid_through,j.collected_at,j.status,"
                + "j.source_url AS original_source_url,j.source_site AS original_source_site,"
                + JobSourceProjection.ACTIVE_URL + " AS source_url," + JobSourceProjection.ACTIVE_SITE
                + " AS source_site FROM market_job_posts j WHERE j.id=? AND j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now())",
            args
        );
    }

    public Map<String, Object> getMarketJobPostsWithCreatorById(
        Object... args) {
        return db.one(
            "SELECT j.*,j.source_url AS original_source_url,j.source_site AS original_source_site,"
                + JobSourceProjection.ACTIVE_URL + " AS source_url," + JobSourceProjection.ACTIVE_SITE
                + " AS source_site," + STAFF_JOB_CREATOR_COLUMNS + " FROM market_job_posts j" + STAFF_JOB_JOINS
                + "WHERE j.id=?",
            args
        );
    }

    public List<Map<String, Object>> listManagedJobs(
        String queryPattern,
        String status,
        UUID createdBy,
        UUID taskId,
        String ingestionMethod,
        int limit,
        int offset) {
        return db.list(
            "SELECT " + PUBLIC_JOB_SUMMARY_COLUMNS + ",j.status,j.collected_at," + STAFF_JOB_CREATOR_COLUMNS
                + " FROM market_job_posts j" + STAFF_JOB_JOINS
                + "WHERE (j.job_title ILIKE ? OR COALESCE(j.company_name,'') ILIKE ?) "
                + "AND (?::varchar IS NULL OR j.status=?) " + "AND (?::uuid IS NULL OR j.created_by_user_id=?::uuid) "
                + "AND (?::uuid IS NULL OR j.task_id=?::uuid) " + "AND (?::varchar IS NULL OR j.ingestion_method=?) "
                + "ORDER BY j.collected_at DESC,j.id LIMIT ? OFFSET ?",
            queryPattern,
            queryPattern,
            status,
            status,
            createdBy,
            createdBy,
            taskId,
            taskId,
            ingestionMethod,
            ingestionMethod,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> listMarketJobSkillsByMarketJobId(
        Object... args) {
        return db.list(
            ("SELECT skill_id,skill_name,normalized_skill,is_required FROM "
                + "market_job_skills WHERE market_job_id=? ORDER BY skill_name"),
            args
        );
    }

    public Object readDatabaseValue(
        Object... args) {
        return db.scalar("SELECT pg_advisory_xact_lock(hashtext('JOB_IMPORT:' || ?))", args);
    }

    public Optional<Map<String, Object>> findMarketJobPostsByRawContentHash(
        Object... args) {
        return db.optional("SELECT * FROM market_job_posts WHERE raw_content_hash=?", args);
    }

    public UUID insertMarketJobPosts(
        Map<String, Object> fields) {
        return db.insert("market_job_posts", fields);
    }

    public void requireActiveAdmin(
        UUID actor) {
        if (db.optional(
            "SELECT id FROM users WHERE id=? AND role IN ('ADMIN','MANAGEMENT') AND status='ACTIVE' "
                + "AND deleted_at IS NULL",
            actor
        ).isEmpty())
            throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .forbidden("Scheduled collection requires an active administrator");
    }

    public Optional<Map<String, Object>> findActiveSource(
        String url,
        String hash) {
        return db.optional(
            "SELECT j.* FROM market_job_sources s JOIN market_job_posts j ON j.id=s.market_job_id "
                + "WHERE s.source_url=? AND s.content_hash=? AND s.status='ACTIVE' AND j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now())",
            url,
            hash
        );
    }

    public Optional<Map<String, Object>> findCrossSourceDuplicate(
        String fingerprint,
        String source) {
        if (fingerprint == null)
            return Optional.empty();
        return db.optional(
            "SELECT j.* FROM market_job_posts j WHERE content_fingerprint=? AND status='ACTIVE' "
                + "AND (valid_through IS NULL OR valid_through>now()) AND source_site<>? ORDER BY collected_at,id LIMIT 1",
            fingerprint,
            source
        );
    }

    public Optional<Map<String, Object>> source(
        String url) {
        return db.optional("SELECT * FROM market_job_sources WHERE source_url=?", url);
    }

    public void saveSource(
        UUID id,
        String site,
        String url,
        String hash,
        java.time.OffsetDateTime deadline) {
        db.update(
            "INSERT INTO market_job_sources(market_job_id,source_site,source_url,content_hash,valid_through) "
                + "VALUES(?,?,?,?,?) ON CONFLICT(source_url) DO UPDATE SET market_job_id=EXCLUDED.market_job_id,"
                + "source_site=EXCLUDED.source_site,content_hash=EXCLUDED.content_hash,"
                + "valid_through=EXCLUDED.valid_through,status='ACTIVE',last_seen_at=now()",
            id,
            site,
            url,
            hash,
            deadline
        );
        db.update(
            "UPDATE market_job_posts j SET valid_through=(SELECT CASE WHEN count(*) FILTER "
                + "(WHERE s.valid_through IS NULL)>0 THEN NULL ELSE max(s.valid_through) END FROM market_job_sources s "
                + "WHERE s.market_job_id=j.id AND s.status='ACTIVE') WHERE j.id=?",
            id
        );
    }

    public void lockJobs(
        List<UUID> ids) {
        db.list(
            "SELECT id FROM market_job_posts WHERE id IN (" + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ") ORDER BY id FOR UPDATE",
            ids.toArray()
        );
    }

    public void retireOrphan(
        UUID id) {
        db.update(
            "UPDATE market_job_posts j SET status='REMOVED' WHERE j.id=? AND status='ACTIVE' AND NOT EXISTS "
                + "(SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE')",
            id
        );
        db.update(
            "UPDATE market_job_embeddings SET is_current=false WHERE market_job_id=? AND EXISTS "
                + "(SELECT 1 FROM market_job_posts j WHERE j.id=market_job_id AND j.status<>'ACTIVE')",
            id
        );
    }

    public void refreshDeadline(
        UUID id) {
        db.update(
            "UPDATE market_job_posts j SET valid_through=(SELECT CASE WHEN count(*) FILTER "
                + "(WHERE s.valid_through IS NULL)>0 THEN NULL ELSE max(s.valid_through) END FROM market_job_sources s "
                + "WHERE s.market_job_id=j.id AND s.status='ACTIVE') WHERE j.id=?",
            id
        );
    }

    public List<Map<String, Object>> sources(
        UUID id) {
        return db.list(
            "SELECT source_site,source_url,status,valid_through,last_seen_at FROM market_job_sources "
                + "WHERE market_job_id=? ORDER BY source_site,source_url",
            id
        );
    }

    public Optional<Map<String, Object>> findSkillByName(
        String name) {
        return db.optional(
            "SELECT id,name FROM skills WHERE is_active AND (lower(name)=lower(?) "
                + "OR replace(lower(slug),'-',' ')=lower(?)) ORDER BY id LIMIT 1",
            name,
            name
        );
    }

    public void updateSourceStatus(
        UUID id,
        String status) {
        db.update(
            "UPDATE market_job_sources SET status=? WHERE market_job_id=?",
            "ACTIVE".equals(status) ? "ACTIVE" : "EXPIRED".equals(status) ? "EXPIRED" : "REMOVED",
            id
        );
    }

    public Map<String, Object> getSkillsById(
        Object... args) {
        return db.one("SELECT id FROM skills WHERE id=? AND is_active", args);
    }

    public int updateMarketJobSkills(
        Object... args) {
        return db.update(
            ("INSERT INTO market_job_skills(market_job_id,skill_id,skill_name,"
                + "normalized_skill,is_required) VALUES(?,?,?,?,?) ON "
                + "CONFLICT(market_job_id,normalized_skill) DO NOTHING"),
            args
        );
    }

    public int updateMarketJobEmbeddingsByMarketJobId(
        Object... args) {
        return db.update(
            ("UPDATE market_job_embeddings SET is_current=false WHERE market_job_id "
                + "IN (SELECT j.id FROM market_job_posts j WHERE source_url=? AND id<>? AND status='ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM market_job_sources s "
                + "WHERE s.market_job_id=j.id AND s.status='ACTIVE'))"),
            args
        );
    }

    public int updateMarketJobPostsBySourceUrl(
        Object... args) {
        return db.update(
            ("UPDATE market_job_posts j SET status='REMOVED' WHERE source_url=? AND id<>? AND status='ACTIVE' "
                + "AND NOT EXISTS (SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id "
                + "AND s.status='ACTIVE')"),
            args
        );
    }

    public UUID insertAuditLogs(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }

    public int updateMarketJobEmbeddingsByMarketJobId2(
        Object... args) {
        return db.update("UPDATE market_job_embeddings SET is_current=false WHERE market_job_id=?", args);
    }

    public int updateMarketJobEmbeddings(
        Object... args) {
        return db.update(
            ("INSERT INTO market_job_embeddings(market_job_id,provider,model,"
                + "embedding,content_hash,is_current) VALUES(?,?,?,CAST(? AS vector),?,"
                + "true) ON CONFLICT(market_job_id,provider,model,content_hash) DO "
                + "UPDATE SET embedding=EXCLUDED.embedding,is_current=true," + "created_at=now()"),
            args
        );
    }

    public UUID insertVectorIndexingJobs(
        Map<String, Object> fields) {
        return db.insert("vector_indexing_jobs", fields);
    }

    public List<Map<String, Object>> indexingJobs(
        UUID id) {
        return db.list(
            "SELECT id,provider,model,content_hash,status,attempt_count,error_message,next_attempt_at,"
                + "started_at,completed_at,created_at FROM vector_indexing_jobs "
                + "WHERE entity_type='JOB' AND entity_id=? ORDER BY created_at DESC,id LIMIT 20",
            id
        );
    }

    public Map<String, Object> getMarketJobPostsById2(
        Object... args) {
        return db.one("SELECT id FROM market_job_posts WHERE id=?", args);
    }

    public int updateMarketJobPostsById(
        Object... args) {
        return db.update("UPDATE market_job_posts SET status=? WHERE id=?", args);
    }

    public int updateMarketJobReviewById(
        String status,
        UUID reviewerId,
        String reviewNote,
        UUID id) {
        return db.update(
            "UPDATE market_job_posts SET status=?,reviewed_by_user_id=?,reviewed_at=now(),review_note=? WHERE id=?",
            status,
            reviewerId,
            reviewNote,
            id
        );
    }

    public boolean isStaffActor(
        UUID actor) {
        return Boolean.TRUE.equals(
            db.scalar(
                "SELECT EXISTS(SELECT 1 FROM users u JOIN management_profiles mp ON mp.user_id=u.id "
                    + "WHERE u.id=? AND u.role='MANAGEMENT' AND mp.sub_role='STAFF')",
                actor
            )
        );
    }

    public void validateAndTouchTask(
        UUID taskId,
        UUID actor,
        boolean strictManagerOrAdmin) {
        Map<String, Object> task = db.optional("SELECT * FROM management_tasks WHERE id=?", taskId).orElseThrow(
            () -> com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .badRequest("Assigned management task not found")
        );
        if ("CANCELLED".equals(task.get("status")))
            throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .conflict("Cannot attach jobs to a cancelled task");
        if (!strictManagerOrAdmin && !actor.equals(task.get("assigned_to_user_id")))
            throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .forbidden("Task is not assigned to the current staff member");
        if ("TODO".equals(task.get("status")))
            db.update("UPDATE management_tasks SET status='IN_PROGRESS',updated_at=now() WHERE id=?", taskId);
    }

    public Map<String, Object> getMarketJobPostsById3(
        Object... args) {
        return getMarketJobPostsWithCreatorById(args);
    }

}
