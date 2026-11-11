package com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Persistence for evaluations requested by an assigned career consultant. */
@Repository
public class ConsultationEvaluationRepository {

    private static final String ATS_SELECT = """
        SELECT a.id, a.user_id, a.resume_id, a.market_job_id, a.match_result_id,
               a.similarity_score, a.ats_score, a.strengths_json, a.missing_skills_json,
               CASE
                   WHEN jsonb_typeof(a.improvement_suggestions_json) = 'array'
                       THEN a.improvement_suggestions_json
                   WHEN jsonb_typeof(a.improvement_suggestions_json -> 'items') = 'array'
                       THEN a.improvement_suggestions_json -> 'items'
                   ELSE '[]'::jsonb
               END AS improvement_suggestions_json,
               a.model_name, a.created_at
        FROM job_resume_analyses a
        JOIN resumes cv ON cv.id = a.resume_id AND cv.user_id = a.user_id
        WHERE cv.status = 'READY'
        """;

    private final SqlStore db;

    public ConsultationEvaluationRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> assignedThread(
        UUID threadId,
        UUID consultantId) {
        return db.one("""
            SELECT t.candidate_user_id,t.consultant_user_id,t.status,t.request_type,t.resume_id,t.job_id,
                   b.status AS booking_status,b.credit_refunded
            FROM consultation_threads t
            JOIN users consultant ON consultant.id = t.consultant_user_id
            JOIN users candidate ON candidate.id = t.candidate_user_id
            LEFT JOIN consultation_bookings b ON b.thread_id=t.id
            WHERE t.id = ?::uuid
              AND consultant.id = ?::uuid
              AND consultant.role = 'CAREER_CONSULTANT'
              AND consultant.status = 'ACTIVE'
              AND candidate.status <> 'DELETED'
            FOR SHARE OF t
            """, threadId, consultantId);
    }

    public Map<String, Object> readyResume(
        UUID resumeId,
        UUID candidateId) {
        return db.one("""
            SELECT cv.id, cv.user_id, cv.title, cv.language, cv.status, cv.source,
                   cv.raw_content, cv.structured_data, cv.updated_at
            FROM resumes cv
            WHERE cv.id = ?::uuid AND cv.user_id = ?::uuid AND cv.status = 'READY'
            FOR SHARE OF cv
            """, resumeId, candidateId);
    }

    public List<Map<String, Object>> listReadyResumes(
        UUID candidateId) {
        return db.list("""
            SELECT id, title, language, status, is_primary, updated_at
            FROM resumes
            WHERE user_id = ?::uuid AND status = 'READY'
            ORDER BY is_primary DESC, updated_at DESC, id DESC
            LIMIT 100
            """, candidateId);
    }

    public Map<String, Object> activeJob(
        UUID jobId) {
        return db.one("""
            SELECT j.id, j.job_title, j.company_name, j.location, j.salary_text,
                   j.role_category, j.raw_content_hash, j.raw_content,
                   j.parsed_description, j.parsed_requirements, j.parsed_responsibilities,
                   j.parsed_benefits, j.parsed_company_overview, j.parsed_seniority,
                   j.employment_type, j.status, j.valid_through
            FROM market_job_posts j
            WHERE j.id = ?::uuid
              AND j.status = 'ACTIVE'
              AND (j.valid_through IS NULL OR j.valid_through > now())
              AND (
                  NOT EXISTS (
                      SELECT 1 FROM market_job_sources source WHERE source.market_job_id = j.id
                  )
                  OR EXISTS (
                      SELECT 1 FROM market_job_sources source
                      WHERE source.market_job_id = j.id AND source.status = 'ACTIVE'
                        AND (source.valid_through IS NULL OR source.valid_through > now())
                  )
              )
            """, jobId);
    }

    public List<Map<String, Object>> attachedReadyResumes(
        UUID candidateId,
        UUID resumeId) {
        return db.list("""
            SELECT id,title,language,status,is_primary,updated_at FROM resumes
            WHERE user_id=? AND id=? AND status='READY'
            """, candidateId, resumeId);
    }

    public List<Map<String, Object>> jobSkills(
        UUID jobId) {
        return db.list("""
            SELECT skill_id, skill_name, normalized_skill, is_required
            FROM market_job_skills
            WHERE market_job_id = ?::uuid
            ORDER BY is_required DESC, normalized_skill, id
            """, jobId);
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public UUID insertResumeAnalysis(
        Map<String, Object> fields) {
        return db.insert("resume_analyses", fields);
    }

    public UUID insertSuggestion(
        Map<String, Object> fields) {
        return db.insert("resume_analysis_suggestions", fields);
    }

    public Map<String, Object> resumeAnalysis(
        UUID analysisId,
        UUID resumeId) {
        return db.one("""
            SELECT a.id, a.resume_id, a.analysis_type, a.overall_score, a.ats_score,
                   a.summary, a.strengths_json, a.issues_json, a.writing_quality_json, a.model_name, a.created_at,
                   COALESCE((
                       SELECT jsonb_agg(to_jsonb(s) ORDER BY s.created_at, s.id)
                       FROM resume_analysis_suggestions s
                       WHERE s.analysis_id = a.id
                   ), '[]'::jsonb) AS suggestions
            FROM resume_analyses a
            WHERE a.id = ?::uuid AND a.resume_id = ?::uuid
            """, analysisId, resumeId);
    }

    public UUID insertAtsAnalysis(
        Map<String, Object> fields) {
        return db.insert("job_resume_analyses", fields);
    }

    public Optional<Map<String, Object>> findAtsAnalysis(
        UUID candidateId,
        UUID resumeId,
        UUID jobId,
        String inputHash) {
        return db.optional(ATS_SELECT + """
              AND a.user_id = ?::uuid AND a.resume_id = ?::uuid AND a.market_job_id = ?::uuid
              AND a.improvement_suggestions_json ->> 'input_hash' = ?
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT 1
            """, candidateId, resumeId, jobId, inputHash);
    }

    public Map<String, Object> atsAnalysis(
        UUID id,
        UUID candidateId,
        UUID resumeId,
        UUID jobId) {
        return db.one(ATS_SELECT + """
              AND a.id = ?::uuid AND a.user_id = ?::uuid
              AND a.resume_id = ?::uuid AND a.market_job_id = ?::uuid
            """, id, candidateId, resumeId, jobId);
    }

    public UUID insertAudit(
        Map<String, Object> fields) {
        return db.insert("audit_logs", fields);
    }
}
