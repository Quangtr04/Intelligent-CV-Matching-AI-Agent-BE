package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceProjection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** SQL persistence operations for the candidate job wishlist. */
@Repository
public class SavedJobRepository {

    private final SqlStore db;

    public SavedJobRepository(
        SqlStore db) {
        this.db = db;
    }

    public boolean activeJobExists(
        UUID jobId) {
        return db.optional(
            "SELECT id FROM market_job_posts WHERE id=? AND status='ACTIVE' "
                + "AND (valid_through IS NULL OR valid_through>now())",
            jobId
        ).isPresent();
    }

    public Map<String, Object> save(
        UUID userId,
        UUID jobId) {
        db.update("INSERT INTO saved_jobs(user_id,market_job_id) VALUES(?,?) ON CONFLICT DO NOTHING", userId, jobId);
        return db.one(
            "SELECT market_job_id AS job_id,created_at AS saved_at FROM saved_jobs "
                + "WHERE user_id=? AND market_job_id=?",
            userId,
            jobId
        );
    }

    public int remove(
        UUID userId,
        UUID jobId) {
        return db.update("DELETE FROM saved_jobs WHERE user_id=? AND market_job_id=?", userId, jobId);
    }

    public List<Map<String, Object>> list(
        UUID userId,
        int limit,
        int offset) {
        return db.list(
            "SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,j.role_category,"
                + "j.posted_at,j.valid_through,j.employment_type,j.status,s.created_at AS saved_at,"
                + "(j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now())) AS available,"
                + JobSourceProjection.ACTIVE_URL + " AS source_url," + JobSourceProjection.ACTIVE_SITE
                + " AS source_site FROM saved_jobs s JOIN market_job_posts j ON j.id=s.market_job_id "
                + "WHERE s.user_id=? ORDER BY s.created_at DESC,j.id LIMIT ? OFFSET ?",
            userId,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> listIds(
        UUID userId) {
        return db.list(
            "SELECT market_job_id AS job_id FROM saved_jobs WHERE user_id=? ORDER BY created_at DESC LIMIT 1000",
            userId
        );
    }
}
