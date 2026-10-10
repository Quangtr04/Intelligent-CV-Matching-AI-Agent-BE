package com.example.intelligent_cv_matching_ai_agent.repository.guest.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by GuestTrialService. */
@Repository
public class GuestTrialRepository {

    private final SqlStore db;

    public GuestTrialRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getResumeTemplatesById(
        Object... args) {
        return db
            .one(("SELECT * FROM resume_templates WHERE id=? AND is_active=true AND " + "is_trial_enabled=true"), args);
    }

    public List<Map<String, Object>> listMarketJobSkillsByMarketJobId(
        Object... args) {
        return db.list(
            ("SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.source_url,COALESCE((SELECT jsonb_agg(jsonb_build_object('name',"
                + "s.normalized_skill,'required',s.is_required)) FROM market_job_skills "
                + "s WHERE s.market_job_id=j.id),'[]'::jsonb) AS skills FROM "
                + "market_job_posts j WHERE j.status='ACTIVE' ORDER BY j.collected_at " + "DESC LIMIT 500"),
            args
        );
    }

    public UUID insertJobMatchingRuns(
        Map<String, Object> fields) {
        return db.insert("job_matching_runs", fields);
    }

    public int updateMatchingRunResumes(
        Object... args) {
        return db.update("INSERT INTO matching_run_resumes(matching_run_id,resume_id) VALUES (?,?)", args);
    }

    public UUID insertJobMatchResults(
        Map<String, Object> fields) {
        return db.insert("job_match_results", fields);
    }

    public Map<String, Object> getResumesById(
        Object... args) {
        return db.one(
            ("SELECT * FROM resumes WHERE id=? AND is_demo=true AND user_id IS NULL " + "AND status='READY'"),
            args
        );
    }

}
