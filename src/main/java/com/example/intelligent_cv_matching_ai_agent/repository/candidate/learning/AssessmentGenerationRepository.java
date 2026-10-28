package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Short transactions only: a database claim fences worker publication and duplicate submissions. */
@Repository
public class AssessmentGenerationRepository {

    private static final String PUBLIC_COLUMNS =
        "id,job_id,status,assessment_id,error_code,message,requested_at,started_at,completed_at";

    private final SqlStore db;

    public AssessmentGenerationRepository(
        SqlStore db) {
        this.db = db;
    }

    public void lockQueue() {
        db.scalar("SELECT pg_advisory_xact_lock(719264031)");
    }

    public boolean activeCandidate(
        UUID owner) {
        return db.optional(
            "SELECT id FROM users WHERE id=? AND role='CANDIDATE' AND status='ACTIVE' AND deleted_at IS NULL",
            owner
        ).isPresent();
    }

    public Optional<Map<String, Object>> active(
        UUID owner,
        UUID job) {
        return db.optional(
            "SELECT " + PUBLIC_COLUMNS + " FROM assessment_generations "
                + "WHERE user_id=? AND job_id=? AND status IN ('QUEUED','RUNNING')",
            owner,
            job
        );
    }

    public long activeCount(
        UUID owner) {
        if (owner == null) {
            return ((Number) db
                .scalar("SELECT count(*) FROM assessment_generations WHERE status IN ('QUEUED','RUNNING')"))
                .longValue();
        }
        return ((Number) db.scalar(
            "SELECT count(*) FROM assessment_generations WHERE user_id=? AND status IN ('QUEUED','RUNNING')",
            owner
        )).longValue();
    }

    public Map<String, Object> enqueue(
        UUID owner,
        UUID job,
        String target) {
        return db.one(
            "INSERT INTO assessment_generations(user_id,job_id,target_job_title) VALUES(?,?,?) RETURNING "
                + PUBLIC_COLUMNS,
            owner,
            job,
            target
        );
    }

    public Map<String, Object> status(
        UUID id,
        UUID owner) {
        return db.one("SELECT " + PUBLIC_COLUMNS + " FROM assessment_generations WHERE id=? AND user_id=?", id, owner);
    }

    public List<Map<String, Object>> latest(
        UUID owner,
        UUID job) {
        String filter = job == null ? "" : " AND job_id=?";
        Object[] args = job == null ? new Object[]{owner} : new Object[]{owner, job};
        return db.list(
            "SELECT " + PUBLIC_COLUMNS + " FROM (SELECT DISTINCT ON(job_id) * FROM assessment_generations "
                + "WHERE user_id=?" + filter + " ORDER BY job_id,requested_at DESC,id DESC) recent "
                + "ORDER BY requested_at DESC,id DESC LIMIT 20",
            args
        );
    }

    public void expireInterrupted() {
        // A crashed process may have consumed quota: never silently call the model again.
        db.update(
            "UPDATE assessment_generations SET status='FAILED',lease_token=NULL,lease_until=NULL,"
                + "completed_at=now(),error_code='INTERRUPTED',"
                + "message='Question generation was interrupted. Retry manually.' "
                + "WHERE status='RUNNING' AND lease_until<=now()"
        );
    }

    public Optional<Map<String, Object>> claimNext(
        UUID token,
        int leaseSeconds) {
        return db.optional(
            "WITH due AS (SELECT id FROM assessment_generations WHERE status='QUEUED' "
                + "AND NOT EXISTS(SELECT 1 FROM assessment_generations WHERE status='RUNNING' AND lease_until>now()) "
                + "ORDER BY requested_at,id FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE assessment_generations q SET status='RUNNING',lease_token=?,"
                + "lease_until=now()+make_interval(secs=>?),started_at=now() FROM due "
                + "WHERE q.id=due.id RETURNING q.*",
            token,
            leaseSeconds
        );
    }

    public boolean ownsClaim(
        UUID id,
        UUID owner,
        UUID token) {
        return db.optional(
            "SELECT id FROM assessment_generations WHERE id=? AND user_id=? AND lease_token=? "
                + "AND status='RUNNING' AND lease_until>now() FOR UPDATE",
            id,
            owner,
            token
        ).isPresent();
    }

    public int complete(
        UUID id,
        UUID token,
        UUID assessment) {
        return db.update(
            "UPDATE assessment_generations SET status='SUCCEEDED',assessment_id=?,lease_token=NULL,"
                + "lease_until=NULL,completed_at=now(),error_code=NULL,message=NULL "
                + "WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>now()",
            assessment,
            id,
            token
        );
    }

    public int fail(
        UUID id,
        UUID token,
        String code,
        String message) {
        return db.update(
            "UPDATE assessment_generations SET status='FAILED',lease_token=NULL,lease_until=NULL,"
                + "completed_at=now(),error_code=?,message=? WHERE id=? AND lease_token=? AND status='RUNNING'",
            code,
            message,
            id,
            token
        );
    }
}
