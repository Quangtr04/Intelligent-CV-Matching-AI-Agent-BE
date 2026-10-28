package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Durable, owner-scoped requests; claim tokens fence publication after profile edits or interrupted workers. */
@Repository
public class ResumeProfileAlignmentRefreshRepository {

    private final SqlStore db;

    public ResumeProfileAlignmentRefreshRepository(
        SqlStore db) {
        this.db = db;
    }

    public boolean isActiveCandidate(
        UUID owner) {
        return db.optional(
            "SELECT id FROM users WHERE id=? AND role='CANDIDATE' AND status='ACTIVE' AND deleted_at IS NULL",
            owner
        ).isPresent();
    }

    public int enqueueReviewedResumes(
        UUID owner,
        String profileHash) {
        return db.update(
            "INSERT INTO resume_profile_alignment_refreshes(resume_id,user_id,requested_profile_hash,status) "
                + "SELECT r.id,r.user_id,?,'QUEUED' FROM resumes r WHERE r.user_id=? AND r.status='READY' "
                + "AND EXISTS(SELECT 1 FROM resume_profile_alignments a WHERE a.resume_id=r.id AND a.user_id=r.user_id) "
                + "ON CONFLICT(resume_id) DO UPDATE SET "
                + "generation=resume_profile_alignment_refreshes.generation+1,"
                + "requested_profile_hash=EXCLUDED.requested_profile_hash,requested_at=now(),"
                + "status=CASE WHEN resume_profile_alignment_refreshes.status='RUNNING' "
                + "AND resume_profile_alignment_refreshes.lease_until>now() THEN 'RUNNING' ELSE 'QUEUED' END,"
                + "lease_token=CASE WHEN resume_profile_alignment_refreshes.status='RUNNING' "
                + "AND resume_profile_alignment_refreshes.lease_until>now() "
                + "THEN resume_profile_alignment_refreshes.lease_token ELSE NULL END,"
                + "lease_until=CASE WHEN resume_profile_alignment_refreshes.status='RUNNING' "
                + "AND resume_profile_alignment_refreshes.lease_until>now() "
                + "THEN resume_profile_alignment_refreshes.lease_until ELSE NULL END,"
                + "started_at=CASE WHEN resume_profile_alignment_refreshes.status='RUNNING' "
                + "AND resume_profile_alignment_refreshes.lease_until>now() "
                + "THEN resume_profile_alignment_refreshes.started_at ELSE NULL END,"
                + "alignment_id=NULL,error_code=NULL,message=NULL,completed_at=NULL",
            profileHash,
            owner
        );
    }

    public Map<String, Object> status(
        UUID resume,
        UUID owner) {
        return db.optional(
            "SELECT status,requested_at,started_at,completed_at,alignment_id,error_code,message "
                + "FROM resume_profile_alignment_refreshes WHERE resume_id=? AND user_id=?",
            resume,
            owner
        ).orElse(Map.of("status", "NONE"));
    }

    public boolean active(
        UUID resume,
        UUID owner) {
        String status = status(resume, owner).get("status").toString();
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    public void expireInterrupted() {
        // A crashed worker may already have consumed quota: never silently replay the same request.
        db.update(
            "UPDATE resume_profile_alignment_refreshes SET status='FAILED',lease_token=NULL,lease_until=NULL,"
                + "completed_at=now(),error_code='INTERRUPTED',message='Automatic comparison was interrupted. Retry manually.' "
                + "WHERE status='RUNNING' AND lease_until<=now()"
        );
    }

    public Optional<Map<String, Object>> claimNext(
        UUID claim,
        int leaseSeconds) {
        return db.optional(
            "WITH due AS (SELECT resume_id FROM resume_profile_alignment_refreshes WHERE status='QUEUED' "
                + "ORDER BY requested_at,resume_id FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE resume_profile_alignment_refreshes q SET status='RUNNING',lease_token=?,"
                + "lease_until=now()+make_interval(secs=>?),started_at=now(),completed_at=NULL "
                + "FROM due WHERE q.resume_id=due.resume_id RETURNING q.*",
            claim,
            leaseSeconds
        );
    }

    public Optional<Map<String, Object>> claim(
        UUID resume,
        UUID owner,
        UUID token,
        boolean lock) {
        return db.optional(
            "SELECT * FROM resume_profile_alignment_refreshes WHERE resume_id=? AND user_id=? "
                + "AND status='RUNNING' AND lease_token=? AND lease_until>now()" + (lock ? " FOR UPDATE" : ""),
            resume,
            owner,
            token
        );
    }

    public int complete(
        UUID resume,
        UUID owner,
        UUID token,
        long generation,
        UUID alignment) {
        return db.update(
            "UPDATE resume_profile_alignment_refreshes SET status='SUCCEEDED',alignment_id=?,error_code=NULL,"
                + "message=NULL,completed_at=now(),lease_token=NULL,lease_until=NULL "
                + "WHERE resume_id=? AND user_id=? AND lease_token=? AND generation=? AND status='RUNNING' "
                + "AND lease_until>now()",
            alignment,
            resume,
            owner,
            token,
            generation
        );
    }

    public void completeManual(
        UUID resume,
        UUID owner,
        String profileHash,
        UUID alignment) {
        db.update(
            "UPDATE resume_profile_alignment_refreshes SET status='SUCCEEDED',alignment_id=?,error_code=NULL,"
                + "message=NULL,completed_at=now(),lease_token=NULL,lease_until=NULL "
                + "WHERE resume_id=? AND user_id=? AND requested_profile_hash=?",
            alignment,
            resume,
            owner,
            profileHash
        );
    }

    public void failOrRequeue(
        UUID resume,
        UUID owner,
        UUID token,
        long generation,
        String terminalStatus,
        String code,
        String message) {
        db.update(
            "UPDATE resume_profile_alignment_refreshes SET "
                + "status=CASE WHEN generation<>? THEN 'QUEUED' ELSE ? END,"
                + "error_code=CASE WHEN generation<>? THEN NULL ELSE ? END,"
                + "message=CASE WHEN generation<>? THEN NULL ELSE ? END,"
                + "completed_at=CASE WHEN generation<>? THEN NULL ELSE now() END,"
                + "started_at=CASE WHEN generation<>? THEN NULL ELSE started_at END,"
                + "lease_token=NULL,lease_until=NULL "
                + "WHERE resume_id=? AND user_id=? AND lease_token=? AND status='RUNNING'",
            generation,
            terminalStatus,
            generation,
            code,
            generation,
            message,
            generation,
            generation,
            resume,
            owner,
            token
        );
    }
}
