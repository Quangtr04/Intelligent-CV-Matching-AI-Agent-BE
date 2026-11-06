package com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by JobCrawlService. */
@Repository
public class JobCrawlRepository {

    private final SqlStore db;

    public JobCrawlRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertMarketCrawlRuns(
        Map<String, Object> fields) {
        return db.insert("market_crawl_runs", fields);
    }

    public Object readDatabaseValue(
        Object... args) {
        return db.scalar("SELECT pg_advisory_xact_lock(hashtext('JOB_IMPORT:' || ?))", args);
    }

    public void requireActiveAdmin(
        UUID actor) {
        if (db.optional(
            "SELECT id FROM users WHERE id=? AND role IN ('ADMIN','MANAGEMENT','MANAGER') AND status='ACTIVE' "
                + "AND deleted_at IS NULL",
            actor
        ).isEmpty())
            throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .forbidden("Scheduled collection requires an active administrator");
    }

    public void expireSource(
        String site,
        String url) {
        db.list(
            "SELECT j.id FROM market_job_posts j JOIN market_job_sources s ON s.market_job_id=j.id "
                + "WHERE s.source_site=? AND s.source_url=? ORDER BY j.id FOR UPDATE OF j",
            site,
            url
        );
        db.update(
            "UPDATE market_job_sources SET status='EXPIRED',last_seen_at=now() "
                + "WHERE source_site=? AND source_url=?",
            site,
            url
        );
        db.update(
            "UPDATE market_job_posts j SET valid_through=(SELECT CASE WHEN count(*)=0 THEN j.valid_through "
                + "WHEN count(*) FILTER "
                + "(WHERE a.valid_through IS NULL)>0 THEN NULL ELSE max(a.valid_through) END FROM market_job_sources a "
                + "WHERE a.market_job_id=j.id AND a.status='ACTIVE') WHERE j.id IN "
                + "(SELECT market_job_id FROM market_job_sources WHERE source_site=? AND source_url=?)",
            site,
            url
        );
    }

    public void updateParsedMetadata(
        String quality,
        java.time.OffsetDateTime parsedAt,
        Object id) {
        db.update(
            "UPDATE market_job_posts SET original_content_quality=?,parsed_at=?,"
                + "ingestion_method=CASE WHEN ingestion_method='MANUAL' THEN 'CRAWL' ELSE ingestion_method END "
                + "WHERE id=?",
            quality,
            parsedAt,
            id
        );
    }

    public void updateDiscoveryCounts(
        int search,
        int discovered,
        int skills,
        UUID run) {
        db.update(
            "UPDATE market_crawl_runs SET search_pages_checked=?,candidate_urls_discovered=?,"
                + "imported_skills=? WHERE id=?",
            search,
            discovered,
            skills,
            run
        );
    }

    public int updateMarketJobEmbeddingsByMarketJobId(
        Object... args) {
        return db.update(
            ("UPDATE market_job_embeddings e SET is_current=false WHERE market_job_id "
                + "IN (SELECT market_job_id FROM market_job_sources WHERE source_site=? AND source_url=?) "
                + "AND NOT EXISTS (SELECT 1 FROM market_job_sources s WHERE s.market_job_id=e.market_job_id "
                + "AND s.status='ACTIVE' AND (s.valid_through IS NULL OR s.valid_through>now()))"),
            args
        );
    }

    public int updateMarketJobPostsByUpper(
        Object... args) {
        return db.update(
            ("UPDATE market_job_posts j SET status='EXPIRED' WHERE j.id IN "
                + "(SELECT market_job_id FROM market_job_sources WHERE source_site=? AND source_url=?) "
                + "AND j.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM market_job_sources s "
                + "WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                + "AND (s.valid_through IS NULL OR s.valid_through>now()))"),
            args
        );
    }

    public Optional<Map<String, Object>> findMarketJobPostsByRawContentHash(
        Object... args) {
        return db.optional("SELECT id FROM market_job_posts WHERE raw_content_hash=? AND status='ACTIVE'", args);
    }

    public int updateMarketJobPostsById(
        Object... args) {
        return db.update(
            ("UPDATE market_job_posts SET posted_at=?,parsed_at=?," + "original_content_quality='json_ld' WHERE id=?"),
            args
        );
    }

    public int updateMarketCrawlRunsById(
        Object... args) {
        return db.update(
            ("UPDATE market_crawl_runs SET status=?,fetched_urls=?,parsed_jobs=?,"
                + "imported_jobs=?,skipped_duplicates=?,detail_pages_checked=?,"
                + "wrong_region_pages=?,rejected_pages=?,message=?,finished_at=? WHERE " + "id=?"),
            args
        );
    }

    public UUID insertAuditLogs(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }

    public Map<String, Object> getMarketCrawlRunsById(
        Object... args) {
        return db.one(
            "SELECT r.*,u.full_name AS requested_by_full_name,u.email AS requested_by_email,"
                + "u.role AS requested_by_role,mp.sub_role AS requested_by_sub_role,t.title AS task_title "
                + "FROM market_crawl_runs r LEFT JOIN users u ON u.id=r.requested_by "
                + "LEFT JOIN management_profiles mp ON mp.user_id=r.requested_by "
                + "LEFT JOIN management_tasks t ON t.id=r.task_id WHERE r.id=?",
            args
        );
    }

    public List<Map<String, Object>> listMarketCrawlItemsByCrawlRunId(
        Object... args) {
        return db.list("SELECT * FROM market_crawl_items WHERE crawl_run_id=? ORDER BY created_at,id", args);
    }

    public List<Map<String, Object>> listMarketCrawlRuns(
        Object... args) {
        return db.list(
            "SELECT r.*,u.full_name AS requested_by_full_name,u.email AS requested_by_email,"
                + "u.role AS requested_by_role,mp.sub_role AS requested_by_sub_role,t.title AS task_title "
                + "FROM market_crawl_runs r LEFT JOIN users u ON u.id=r.requested_by "
                + "LEFT JOIN management_profiles mp ON mp.user_id=r.requested_by "
                + "LEFT JOIN management_tasks t ON t.id=r.task_id ORDER BY r.started_at DESC,r.id LIMIT ? OFFSET ?",
            args
        );
    }

    public UUID insertMarketCrawlItems(
        Map<String, Object> fields) {
        return db.insert("market_crawl_items", fields);
    }

}
