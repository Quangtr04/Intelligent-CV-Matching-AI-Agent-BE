package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by JobService. */
@Repository("marketJobRepository")
public class JobRepository {

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
            ("SELECT id,job_title,company_name,location,salary_text,role_category,"
                + "posted_at,source_site,source_url FROM market_job_posts WHERE "
                + "status='ACTIVE' AND job_title ILIKE ? AND COALESCE(location,'') ILIKE "
                + "? ORDER BY collected_at DESC,id LIMIT ? OFFSET ?"),
            args
        );
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one("SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE'", args);
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
                + "IN (SELECT id FROM market_job_posts WHERE source_url=? AND id<>? AND " + "status='ACTIVE')"),
            args
        );
    }

    public int updateMarketJobPostsBySourceUrl(
        Object... args) {
        return db.update(
            ("UPDATE market_job_posts SET status='REMOVED' WHERE source_url=? AND " + "id<>? AND status='ACTIVE'"),
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

    public Map<String, Object> getMarketJobPostsById2(
        Object... args) {
        return db.one("SELECT id FROM market_job_posts WHERE id=?", args);
    }

    public int updateMarketJobPostsById(
        Object... args) {
        return db.update("UPDATE market_job_posts SET status=? WHERE id=?", args);
    }

    public Map<String, Object> getMarketJobPostsById3(
        Object... args) {
        return db.one("SELECT * FROM market_job_posts WHERE id=?", args);
    }

}
