package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by CareerToolsService. */
@Repository
public class CareerToolsRepository {

    private final SqlStore db;

    public CareerToolsRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listJobMatchingRunsByUserId(
        Object... args) {
        return db.list(
            ("SELECT id,basis,provider,model,status,started_at,completed_at FROM "
                + "job_matching_runs WHERE user_id=? ORDER BY started_at DESC LIMIT ?"),
            args
        );
    }

    public Map<String, Object> getJobMatchResultsById(
        Object... args) {
        return db.one(
            ("SELECT r.id FROM job_match_results r JOIN job_matching_runs m ON "
                + "m.id=r.matching_run_id WHERE r.id=? AND m.user_id=?"),
            args
        );
    }

    public int updateJobMatchFeedback(
        Object... args) {
        return db.update(
            ("INSERT INTO job_match_feedback(user_id,match_result_id,feedback_type,"
                + "rating,comment) VALUES(?,?,?,?,?) ON CONFLICT(user_id,"
                + "match_result_id) DO UPDATE SET feedback_type=EXCLUDED.feedback_type,"
                + "rating=EXCLUDED.rating,comment=EXCLUDED.comment"),
            args
        );
    }

    public Map<String, Object> getJobMatchFeedbackByUserId(
        Object... args) {
        return db.one("SELECT * FROM job_match_feedback WHERE user_id=? AND match_result_id=?", args);
    }

    public Optional<Map<String, Object>> findJobResumeAnalysesById(
        Object... args) {
        return db.optional(
            ("SELECT * FROM job_resume_analyses WHERE id=? AND user_id=? AND " + "resume_id=? AND market_job_id=? AND "
                + "improvement_suggestions_json->>'input_hash'=?"),
            args
        );
    }

    public Optional<Map<String, Object>> findJobResumeAnalysesByUserId(
        Object... args) {
        return db.optional(
            ("SELECT * FROM job_resume_analyses WHERE user_id=? AND resume_id=? AND "
                + "market_job_id=? AND improvement_suggestions_json->>'input_hash'=? "
                + "ORDER BY created_at DESC LIMIT 1"),
            args
        );
    }

    public UUID insertJobResumeAnalyses(
        Map<String, Object> fields) {
        return db.insert("job_resume_analyses", fields);
    }

    public Map<String, Object> getJobResumeAnalysesById(
        Object... args) {
        return db.one("SELECT * FROM job_resume_analyses WHERE id=?", args);
    }

    public List<Map<String, Object>> listMarketJobSkillsByMarketJobId(
        Object... args) {
        return db.list("SELECT skill_name,is_required FROM market_job_skills WHERE market_job_id=?", args);
    }

    public UUID insertApplicationDrafts(
        Map<String, Object> fields) {
        return db.insert("application_drafts", fields);
    }

    public List<Map<String, Object>> listApplicationDraftsById(
        Object... args) {
        return db.list("SELECT * FROM application_drafts WHERE id IN (?,?)", args);
    }

    public List<Map<String, Object>> listApplicationDraftsByUserId(
        Object... args) {
        return db.list("SELECT * FROM application_drafts WHERE user_id=? ORDER BY updated_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listApplicationDraftsByUserId2(
        Object... args) {
        return db.list(
            ("SELECT * FROM application_drafts WHERE user_id=? AND market_job_id=? "
                + "ORDER BY updated_at DESC LIMIT 100"),
            args
        );
    }

    public Map<String, Object> getApplicationDraftsById(
        Object... args) {
        return db.one("SELECT id FROM application_drafts WHERE id=? AND user_id=?", args);
    }

    public int updateApplicationDraftsById(
        Object... args) {
        return db.update("UPDATE application_drafts SET content=?,updated_at=now() WHERE id=?", args);
    }

    public Map<String, Object> getApplicationDraftsById2(
        Object... args) {
        return db.one("SELECT * FROM application_drafts WHERE id=?", args);
    }

    public UUID insertExternalApplyEvents(
        Map<String, Object> fields) {
        return db.insert("external_apply_events", fields);
    }

    public Map<String, Object> getCandidateProfilesByUserId(
        Object... args) {
        return db.one("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public UUID insertSalaryEstimations(
        Map<String, Object> fields) {
        return db.insert("salary_estimations", fields);
    }

    public Map<String, Object> getSalaryEstimationsById(
        Object... args) {
        return db.one("SELECT * FROM salary_estimations WHERE id=?", args);
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one("SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE'", args);
    }

}
