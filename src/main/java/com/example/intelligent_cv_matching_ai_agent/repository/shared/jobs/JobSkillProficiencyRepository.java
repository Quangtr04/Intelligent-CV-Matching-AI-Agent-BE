package com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JobSkillProficiencyRepository {

    private static final String ACTIVE = "j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now())";

    private static final String STATE = "coalesce(j.skill_proficiency_status,'PENDING')";

    private static final String INPUT_KEY =
        "(? || ':' || md5(concat_ws('|', j.raw_content_hash, j.original_content, j.parsed_requirements,"
            + "j.requirement_extraction::text, j.requirement_extraction_verified::text)))";

    private final SqlStore db;

    public JobSkillProficiencyRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> statusCounts() {
        return db.list(
            "SELECT " + STATE + " AS status,count(*) AS total FROM market_job_posts j WHERE " + ACTIVE + " GROUP BY 1"
        );
    }

    public List<Map<String, Object>> statusJobs(
        String status,
        int limit,
        int offset) {
        return db.list(
            "SELECT j.id,j.job_title,j.company_name," + STATE + " AS status,"
                + "j.skill_proficiency_attempts AS attempts,j.skill_proficiency_updated_at AS updated_at,"
                + "j.skill_proficiency_error AS error_code,"
                + "CASE WHEN j.skill_proficiency_status='RETRY' THEN j.skill_proficiency_retry_at END AS retry_at,"
                + "j.skill_proficiency_analysis->>'rubric_version' AS rubric_version,"
                + "coalesce(jsonb_array_length(j.skill_proficiency_analysis->'results'),0) AS skill_count "
                + "FROM market_job_posts j WHERE " + ACTIVE + " AND " + STATE + "=? "
                + "ORDER BY j.skill_proficiency_updated_at DESC NULLS LAST,j.id LIMIT ? OFFSET ?",
            status,
            limit,
            offset
        );
    }

    public Optional<Map<String, Object>> claim(
        String version,
        UUID lease,
        UUID fallbackActor) {
        return db.optional(
            "WITH inputs AS (SELECT j.id," + INPUT_KEY + " AS input_key, a.id AS actor "
                + "FROM market_job_posts j JOIN LATERAL (SELECT u.id FROM users u WHERE u.status='ACTIVE' "
                + "AND u.role IN ('ADMIN','MANAGER') AND (u.id=j.created_by_user_id OR u.id=?) "
                + "ORDER BY (u.id=j.created_by_user_id) DESC NULLS LAST LIMIT 1) a ON true "
                + "WHERE j.status='ACTIVE' AND (j.valid_through IS NULL OR j.valid_through>now())), "
                + "candidate AS (SELECT i.* FROM inputs i JOIN market_job_posts j ON j.id=i.id "
                + "WHERE (j.skill_proficiency_input_key IS DISTINCT FROM i.input_key "
                + "AND (j.skill_proficiency_lease IS NULL OR j.skill_proficiency_retry_at<=now())) "
                + "OR (j.skill_proficiency_status IN ('PROCESSING','RETRY') AND j.skill_proficiency_retry_at<=now()) "
                + "ORDER BY j.skill_proficiency_retry_at NULLS FIRST,j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1) "
                + "UPDATE market_job_posts j SET skill_proficiency_attempts=CASE "
                + "WHEN j.skill_proficiency_input_key=c.input_key THEN j.skill_proficiency_attempts+1 ELSE 1 END, "
                + "skill_proficiency_input_key=c.input_key,skill_proficiency_lease=?, "
                + "skill_proficiency_status='PROCESSING',skill_proficiency_retry_at=now()+interval '10 minutes', "
                + "skill_proficiency_updated_at=now(),skill_proficiency_error=NULL "
                + "FROM candidate c WHERE j.id=c.id RETURNING j.*,c.actor",
            version,
            fallbackActor,
            lease
        );
    }

    public int retryFailed() {
        return db.update(retrySql() + "j.skill_proficiency_status='FAILED'");
    }

    public int retry(
        UUID jobId) {
        return db.update(
            retrySql() + "j.id=? AND (j.skill_proficiency_status='FAILED' OR "
                + "(j.skill_proficiency_status='RETRY' AND j.skill_proficiency_retry_at>now()))",
            jobId
        );
    }

    private static String retrySql() {
        return "UPDATE market_job_posts j SET skill_proficiency_attempts=CASE "
            + "WHEN j.skill_proficiency_status='FAILED' THEN 0 ELSE j.skill_proficiency_attempts END, "
            + "skill_proficiency_status='RETRY',skill_proficiency_retry_at=now(),skill_proficiency_updated_at=now() "
            + "WHERE " + ACTIVE + " AND j.skill_proficiency_lease IS NULL AND ";
    }

    public void complete(
        UUID job,
        UUID lease,
        String version,
        Map<String, Object> analysis) {
        db.update(
            "UPDATE market_job_posts j SET skill_proficiency_analysis=?::jsonb,skill_proficiency_lease=NULL, "
                + "skill_proficiency_status='COMPLETED',skill_proficiency_retry_at=NULL, "
                + "skill_proficiency_updated_at=now(),skill_proficiency_error=NULL "
                + "WHERE j.id=? AND j.skill_proficiency_lease=? AND j.skill_proficiency_input_key=" + INPUT_KEY,
            db.json(analysis),
            job,
            lease,
            version
        );
    }

    public void fail(
        UUID job,
        UUID lease,
        String errorCode) {
        db.update(
            "UPDATE market_job_posts SET skill_proficiency_lease=NULL, "
                + "skill_proficiency_status=CASE WHEN skill_proficiency_attempts>=3 THEN 'FAILED' ELSE 'RETRY' END, "
                + "skill_proficiency_retry_at=now()+make_interval(secs=>60*skill_proficiency_attempts), "
                + "skill_proficiency_updated_at=now(),skill_proficiency_error=? "
                + "WHERE id=? AND skill_proficiency_lease=?",
            errorCode,
            job,
            lease
        );
    }
}
