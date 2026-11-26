package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class CandidateAiTaskRepository {

    private final SqlStore db;

    public CandidateAiTaskRepository(
        SqlStore db) {
        this.db = db;
    }

    public void expire(
        UUID owner) {
        db.update(
            "UPDATE candidate_ai_requests SET status='FAILED',completed_at=now(),"
                + "message='AI task was interrupted. Please try again.' "
                + "WHERE user_id=? AND status='RUNNING' AND lease_until<=now()",
            owner
        );
    }

    public boolean active(
        UUID owner) {
        return db
            .optional("SELECT id FROM candidate_ai_requests WHERE user_id=? AND status IN ('QUEUED','RUNNING')", owner)
            .isPresent();
    }

    public Map<String, Object> enqueueRoadmap(
        UUID owner,
        Object input) {
        return db.one(
            "INSERT INTO candidate_ai_requests(user_id,kind,input) VALUES(?,'ROADMAP',?) RETURNING id,status",
            owner,
            db.json(input)
        );
    }

    public UUID startCall(
        UUID owner,
        String feature) {
        return (UUID) db.one(
            "INSERT INTO candidate_ai_requests(user_id,kind,status,input,lease_until) "
                + "VALUES(?,'AI','RUNNING',?,now()+interval '30 minutes') RETURNING id",
            owner,
            db.json(Map.of("feature", feature))
        ).get("id");
    }

    public Optional<Map<String, Object>> claimRoadmap() {
        return db.optional(
            "WITH due AS (SELECT id FROM candidate_ai_requests WHERE status='QUEUED' "
                + "ORDER BY requested_at,id FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE candidate_ai_requests task SET status='RUNNING',lease_until=now()+interval '60 minutes' "
                + "FROM due WHERE task.id=due.id RETURNING task.*"
        );
    }

    public int finish(
        UUID id,
        String status,
        UUID result,
        String message) {
        return db.update(
            "UPDATE candidate_ai_requests SET status=?,result_id=?,message=?,completed_at=now(),lease_until=NULL "
                + "WHERE id=? AND status='RUNNING' AND lease_until>now()",
            status,
            result,
            message,
            id
        );
    }

    public int cancel(
        UUID owner,
        UUID id) {
        return db.update(
            "UPDATE candidate_ai_requests SET status='FAILED',completed_at=now(),lease_until=NULL,message=? "
                + "WHERE user_id=? AND id=? AND status IN ('QUEUED','RUNNING')",
            "AI task was cancelled.",
            owner,
            id
        );
    }

    public boolean running(
        UUID id) {
        return db.optional(
            "SELECT id FROM candidate_ai_requests WHERE id=? AND status='RUNNING' AND lease_until>now() "
                + "UNION ALL SELECT id FROM assessment_generations WHERE id=? AND status='RUNNING' AND lease_until>now()",
            id,
            id
        ).isPresent();
    }

    public List<Map<String, Object>> recent(
        UUID owner) {
        return db.list(
            "SELECT * FROM (SELECT id,kind,CASE WHEN message='AI task was cancelled.' "
                + "THEN 'CANCELLED' ELSE status END AS status,result_id,message,requested_at,completed_at,"
                + "input->>'jobId' AS job_id,input->>'assessmentId' AS assessment_id,"
                + "input->>'feature' AS feature FROM candidate_ai_requests WHERE user_id=? "
                + "UNION ALL SELECT id,'ASSESSMENT',CASE WHEN error_code='CANCELLED' "
                + "THEN 'CANCELLED' ELSE status END,assessment_id,message,requested_at,completed_at,"
                + "job_id::text,NULL,NULL FROM assessment_generations WHERE user_id=?) tasks "
                + "ORDER BY (status IN ('QUEUED','RUNNING')) DESC, requested_at DESC,id DESC LIMIT 25",
            owner,
            owner
        );
    }
}
