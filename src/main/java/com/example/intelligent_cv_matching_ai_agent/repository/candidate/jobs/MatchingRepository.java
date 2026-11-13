package com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceProjection;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by MatchingService. */
@Repository
public class MatchingRepository {

    private final SqlStore db;

    public MatchingRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getJobMatchingRunsById(
        Object... args) {
        return db.one("SELECT * FROM job_matching_runs WHERE id=? AND user_id=?", args);
    }

    public List<Map<String, Object>> listJobMatchResultsByMatchingRunId(
        Object... args) {
        return db.list(
            ("SELECT r.*,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.valid_through,j.source_url AS original_source_url,") + JobSourceProjection.ACTIVE_URL
                + " AS source_url," + JobSourceProjection.ACTIVE_SITE + " AS source_site,"
                + "CASE WHEN j.status='ACTIVE' AND j.valid_through<=now() THEN 'EXPIRED' "
                + "ELSE j.status END AS job_status FROM job_match_results r JOIN "
                + ("market_job_posts j ON j.id=r.market_job_id WHERE r.matching_run_id=? " + "ORDER BY rank_position"),
            args
        );
    }

    public List<Map<String, Object>> listResumesByUserId(
        Object... args) {
        return db.list(
            ("SELECT * FROM resumes WHERE user_id=? AND status='READY' AND NOT " + "is_demo ORDER BY id LIMIT 101"),
            args
        );
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public List<Map<String, Object>> listCandidateProfileSkillsByUserId(
        Object... args) {
        return db.list(
            ("SELECT s.name,ps.proficiency_level FROM candidate_profile_skills ps "
                + "JOIN skills s ON s.id=ps.skill_id WHERE ps.user_id=? ORDER BY s.name"),
            args
        );
    }

    public List<Map<String, Object>> listMarketJobSkillsByMarketJobId(
        Object... args) {
        return db.list(
            ("SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.source_url AS original_source_url,") + JobSourceProjection.ACTIVE_URL + " AS source_url,"
                + JobSourceProjection.ACTIVE_SITE + " AS source_site,"
                + ("j.raw_content_hash,j.original_content,j.raw_content,"
                    + "j.requirement_extraction,j.requirement_extraction_verified,"
                    + "j.parsed_requirements,j.parsed_seniority,COALESCE((SELECT ")
                + "jsonb_agg(jsonb_build_object('name',s.normalized_skill,'required',"
                + "s.is_required) ORDER BY s.normalized_skill) FROM market_job_skills s "
                + "WHERE s.market_job_id=j.id),'[]'::jsonb) AS skills FROM "
                + "market_job_posts j WHERE j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now()) ORDER BY j.id LIMIT 5000",
            args
        );
    }

    public List<Map<String, Object>> listActiveJobSalaryMetadata() {
        return db.list(
            ("SELECT id,salary_text FROM market_job_posts WHERE status='ACTIVE' AND "
                + "(valid_through IS NULL OR valid_through>now()) ORDER BY id")
        );
    }

    public List<Map<String, Object>> listMarketJobSkillsByIds(
        String jobIds) {
        return db.list(
            ("SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.valid_through,j.source_url AS original_source_url,") + JobSourceProjection.ACTIVE_URL
                + " AS source_url," + JobSourceProjection.ACTIVE_SITE
                + " AS source_site,j.raw_content_hash,j.original_content,j.raw_content,"
                + ("j.requirement_extraction,j.requirement_extraction_verified,"
                    + "j.parsed_requirements,j.parsed_seniority,")
                + "COALESCE((SELECT "
                + "jsonb_agg(jsonb_build_object('name',s.normalized_skill,'required',s.is_required) "
                + ("ORDER BY s.normalized_skill) FROM market_job_skills s WHERE "
                    + "s.market_job_id=j.id),'[]'::jsonb) ")
                + "AS skills FROM market_job_posts j WHERE j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now()) "
                + "AND j.id=ANY(CAST(? AS uuid[])) ORDER BY j.id",
            jobIds
        );
    }

    public Object readMarketJobPostsByStatus(
        Object... args) {
        return db.scalar(
            ("SELECT md5(COALESCE(string_agg(jsonb_build_array(j.id,"
                + "j.raw_content_hash,j.job_title,j.company_name,j.location,"
                + "j.salary_text,j.valid_through,j.source_url,j.requirement_extraction,"
                + "j.requirement_extraction_verified,j.parsed_requirements," + "j.parsed_seniority,COALESCE((SELECT "
                + "jsonb_agg(jsonb_build_array(s.normalized_skill,s.is_required) ORDER "
                + "BY s.normalized_skill,s.is_required) FROM market_job_skills s WHERE "
                + "s.market_job_id=j.id),'[]'::jsonb),") + JobSourceProjection.ACTIVE_URL + ","
                + JobSourceProjection.ACTIVE_SITE
                + ")::text,',' ORDER BY j.id),'')) FROM market_job_posts j WHERE j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now())",
            args
        );
    }

    public Object readMarketJobEmbeddingsByStatus(
        Object... args) {
        return db.scalar(
            ("SELECT CASE WHEN count(*)=0 THEN '' ELSE "
                + "md5(COALESCE(string_agg(e.id::text||':'||e.content_hash||':'||e.create"
                + "d_at::text,',' ORDER BY e.id),'')) END FROM market_job_embeddings e "
                + "JOIN market_job_posts j ON j.id=e.market_job_id WHERE "
                + "j.status='ACTIVE' AND (j.valid_through IS NULL OR "
                + "j.valid_through>now()) AND e.is_current AND e.embedding IS NOT NULL "
                + "AND e.provider=? AND e.model=? AND e.content_hash=j.raw_content_hash"),
            args
        );
    }

    public Optional<Map<String, Object>> findJobMatchingRunsByUserId(
        Object... args) {
        return db.optional(
            ("SELECT id FROM job_matching_runs WHERE user_id=? AND "
                + "status='COMPLETED' AND profile_snapshot->>'request_hash'=? AND "
                + "completed_at>now()-interval '1 hour' ORDER BY completed_at DESC LIMIT " + "1"),
            args
        );
    }

    public UUID insertJobMatchingRuns(
        Map<String, Object> fields) {
        return db.insert("job_matching_runs", fields);
    }

    public int updateMatchingRunResumes(
        Object... args) {
        return db.update("INSERT INTO matching_run_resumes(matching_run_id,resume_id) VALUES(?,?)", args);
    }

    public List<Map<String, Object>> listMarketJobEmbeddingsByStatus(
        Object... args) {
        return db.list(
            ("SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.source_url AS original_source_url,") + JobSourceProjection.ACTIVE_URL + " AS source_url,"
                + JobSourceProjection.ACTIVE_SITE + " AS source_site,"
                + "j.raw_content,1-(e.embedding <=> CAST(? AS vector)) AS vector_score "
                + "FROM market_job_embeddings e JOIN market_job_posts j ON "
                + "j.id=e.market_job_id WHERE j.status='ACTIVE' "
                + "AND (j.valid_through IS NULL OR j.valid_through>now()) AND e.is_current AND "
                + ("e.provider=? AND e.model=? AND e.embedding IS NOT NULL AND " + "e.content_hash=j.raw_content_hash ")
                + "ORDER BY e.embedding <=> CAST(? AS vector) LIMIT ?",
            args
        );
    }

    public List<Map<String, Object>> listMarketJobEmbeddingsByStatusForIds(
        Object... args) {
        return db.list(
            ("WITH eligible_embeddings AS MATERIALIZED (SELECT e.market_job_id,"
                + "e.embedding FROM market_job_embeddings e JOIN market_job_posts j ON "
                + "j.id=e.market_job_id WHERE j.status='ACTIVE' AND (j.valid_through IS "
                + "NULL OR j.valid_through>now()) AND e.is_current AND e.provider=? AND "
                + "e.model=? AND e.embedding IS NOT NULL AND "
                + "e.content_hash=j.raw_content_hash AND j.id=ANY(CAST(? AS uuid[]))) "
                + "SELECT j.id,j.job_title,j.company_name,j.location,j.salary_text,"
                + "j.raw_content,1-(e.embedding <=> CAST(? AS vector)) AS vector_score "
                + "FROM eligible_embeddings e JOIN market_job_posts j ON "
                + "j.id=e.market_job_id ORDER BY e.embedding <=> CAST(? AS vector),j.id " + "LIMIT ?"),
            args
        );
    }

    public UUID insertJobMatchResults(
        Map<String, Object> fields) {
        return db.insert("job_match_results", fields);
    }

    public int updateJobMatchingRunsById(
        Object... args) {
        return db.update("UPDATE job_matching_runs SET status='COMPLETED',completed_at=now() WHERE id=?", args);
    }

    public int updateJobMatchingRunsMode(
        UUID runId,
        String mode,
        String provider,
        String model) {
        return db.update(
            ("UPDATE job_matching_runs SET provider=?,model=?,"
                + "profile_snapshot=jsonb_set(profile_snapshot,'{mode}',to_jsonb(CAST(? " + "AS text))) WHERE id=?"),
            provider,
            model,
            mode,
            runId
        );
    }

}
