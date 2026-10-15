package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.springframework.stereotype.Repository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable claims and fenced writes for job embedding work. */
@Repository
public class JobEmbeddingRepository {

    private final SqlStore db;

    public JobEmbeddingRepository(
        SqlStore db) {
        this.db = db;
    }

    public Optional<Map<String, Object>> findJobForUpdate(
        UUID id) {
        return db.optional("SELECT * FROM market_job_posts WHERE id=? FOR UPDATE", id);
    }

    public boolean isActiveAdministrator(
        UUID actor) {
        return db.optional(
            "SELECT id FROM users WHERE id=? AND role='ADMIN' AND status='ACTIVE' AND deleted_at IS NULL",
            actor
        ).isPresent();
    }

    public boolean hasCurrentEmbedding(
        UUID job,
        String hash,
        String provider,
        String model) {
        return db.optional(
            ("SELECT id FROM market_job_embeddings WHERE market_job_id=? AND content_hash=? "
                + "AND provider=? AND model=? AND is_current AND embedding IS NOT NULL"),
            job,
            hash,
            provider,
            model
        ).isPresent();
    }

    public UUID enqueue(
        UUID job,
        String hash,
        UUID actor,
        String provider,
        String model,
        String status,
        String error) {
        return (UUID) db.scalar(
            ("INSERT INTO vector_indexing_jobs(entity_type,entity_id,operation,provider,model,"
                + "content_hash,requested_by,status,attempt_count,next_attempt_at,error_message,completed_at) "
                + "VALUES('JOB',?,'INDEX',?,?,?,?,?,0,now(),?,"
                + "CASE WHEN ? IN ('COMPLETED','CANCELLED') THEN now() ELSE NULL END) "
                + "ON CONFLICT(entity_type,entity_id,operation,provider,model,content_hash) "
                + "WHERE entity_type='JOB' AND operation='INDEX' AND content_hash IS NOT NULL DO UPDATE SET "
                + "status=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.status ELSE EXCLUDED.status END,"
                + "requested_by=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.requested_by ELSE EXCLUDED.requested_by END,"
                + "attempt_count=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.attempt_count ELSE 0 END,"
                + "next_attempt_at=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.next_attempt_at ELSE now() END,"
                + "lease_token=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.lease_token ELSE NULL END,"
                + "lease_until=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.lease_until ELSE NULL END,"
                + "started_at=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.started_at ELSE NULL END,"
                + "completed_at=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.completed_at ELSE EXCLUDED.completed_at END,"
                + "error_message=CASE WHEN vector_indexing_jobs.status IN ('QUEUED','PROCESSING') "
                + "THEN vector_indexing_jobs.error_message ELSE EXCLUDED.error_message END RETURNING id"),
            job,
            provider,
            model,
            hash,
            actor,
            status,
            error,
            status
        );
    }

    public Optional<Map<String, Object>> claimNext(
        String provider,
        String model,
        UUID lease,
        int leaseSeconds) {
        return claim(null, provider, model, lease, leaseSeconds);
    }

    public Optional<Map<String, Object>> claimById(
        UUID id,
        String provider,
        String model,
        UUID lease,
        int leaseSeconds) {
        return claim(id, provider, model, lease, leaseSeconds);
    }

    private Optional<Map<String, Object>> claim(
        UUID id,
        String provider,
        String model,
        UUID lease,
        int leaseSeconds) {
        String selection = id == null ? "" : " AND id=?";
        String sql = ("WITH due AS (SELECT id FROM vector_indexing_jobs WHERE entity_type='JOB' "
            + "AND operation='INDEX' AND provider=? AND model=? " + "AND ((status='QUEUED' AND next_attempt_at<=now()) "
            + "OR (status='PROCESSING' AND lease_until<=now()))")
            + selection
            + (" ORDER BY next_attempt_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE vector_indexing_jobs j SET "
                + "status=CASE WHEN j.attempt_count>=3 THEN 'FAILED' ELSE 'PROCESSING' END,"
                + "attempt_count=CASE WHEN j.attempt_count>=3 THEN j.attempt_count ELSE j.attempt_count+1 END,"
                + "started_at=now(),completed_at=CASE WHEN j.attempt_count>=3 THEN now() ELSE NULL END,"
                + "lease_token=CASE WHEN j.attempt_count>=3 THEN NULL ELSE ? END,"
                + "lease_until=CASE WHEN j.attempt_count>=3 THEN NULL " + "ELSE now()+make_interval(secs=>?) END,"
                + "error_message=CASE WHEN j.attempt_count>=3 THEN 'Embedding attempt limit reached' "
                + "ELSE j.error_message END FROM due WHERE j.id=due.id RETURNING j.*");

        return id == null
            ? db.optional(sql, provider, model, lease, leaseSeconds)
            : db.optional(sql, provider, model, id, lease, leaseSeconds);
    }

    public Optional<Map<String, Object>> findLeaseForUpdate(
        UUID id,
        UUID lease) {
        return db.optional(
            ("SELECT * FROM vector_indexing_jobs WHERE id=? AND lease_token=? AND status='PROCESSING' "
                + "AND lease_until>now() FOR UPDATE"),
            id,
            lease
        );
    }

    public Map<String, Object> task(
        UUID id) {
        return db.one(
            ("SELECT id,entity_id,provider,model,content_hash,status,attempt_count,error_message,"
                + "next_attempt_at,lease_until FROM vector_indexing_jobs WHERE id=?"),
            id
        );
    }

    public int complete(
        UUID id,
        UUID lease) {
        return db.update(
            ("UPDATE vector_indexing_jobs SET status='COMPLETED',completed_at=now(),error_message=NULL,"
                + "lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND status='PROCESSING'"),
            id,
            lease
        );
    }

    public int cancel(
        UUID id,
        UUID lease,
        String reason) {
        return db.update(
            ("UPDATE vector_indexing_jobs SET status='CANCELLED',completed_at=now(),error_message=?,"
                + "lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND status='PROCESSING'"),
            reason,
            id,
            lease
        );
    }

    public int retryOrFail(
        UUID id,
        UUID lease,
        String error,
        int delaySeconds) {
        return db.update(
            ("UPDATE vector_indexing_jobs SET status=CASE WHEN attempt_count>=3 THEN 'FAILED' ELSE 'QUEUED' END,"
                + "completed_at=CASE WHEN attempt_count>=3 THEN now() ELSE NULL END,error_message=?,"
                + "next_attempt_at=now()+make_interval(secs=>?),lease_token=NULL,lease_until=NULL "
                + "WHERE id=? AND lease_token=? AND status='PROCESSING'"),
            error,
            delaySeconds,
            id,
            lease
        );
    }

    public void replaceCurrentEmbedding(
        UUID job,
        String provider,
        String model,
        String hash,
        String vector) {
        db.update("UPDATE market_job_embeddings SET is_current=false WHERE market_job_id=?", job);
        db.update(
            ("INSERT INTO market_job_embeddings(market_job_id,provider,model,embedding,content_hash,is_current) "
                + "VALUES(?,?,?,CAST(? AS vector),?,true) " + "ON CONFLICT(market_job_id,provider,model,content_hash) "
                + "DO UPDATE SET embedding=EXCLUDED.embedding,is_current=true,created_at=now()"),
            job,
            provider,
            model,
            vector,
            hash
        );
    }
}
