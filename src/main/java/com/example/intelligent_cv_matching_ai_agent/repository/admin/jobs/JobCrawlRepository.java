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

    public int updateMarketJobEmbeddingsByMarketJobId(
        Object... args) {
        return db.update(
            ("UPDATE market_job_embeddings SET is_current=false WHERE market_job_id "
                + "IN (SELECT id FROM market_job_posts WHERE upper(source_site)=? AND " + "source_url=?)"),
            args
        );
    }

    public int updateMarketJobPostsByUpper(
        Object... args) {
        return db.update(
            ("UPDATE market_job_posts SET status='EXPIRED' WHERE "
                + "upper(source_site)=? AND source_url=? AND status='ACTIVE'"),
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
        return db.one("SELECT * FROM market_crawl_runs WHERE id=?", args);
    }

    public List<Map<String, Object>> listMarketCrawlItemsByCrawlRunId(
        Object... args) {
        return db.list("SELECT * FROM market_crawl_items WHERE crawl_run_id=? ORDER BY created_at,id", args);
    }

    public List<Map<String, Object>> listMarketCrawlRuns(
        Object... args) {
        return db.list("SELECT * FROM market_crawl_runs ORDER BY started_at DESC,id LIMIT ? OFFSET ?", args);
    }

    public UUID insertMarketCrawlItems(
        Map<String, Object> fields) {
        return db.insert("market_crawl_items", fields);
    }

}
