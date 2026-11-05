package com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Read-only report queries for an authorized consultation's candidate. */
@Repository
public class ConsultationAnalysisRepository {

    private final SqlStore db;

    public ConsultationAnalysisRepository(
        SqlStore db) {
        this.db = db;
    }

    /** The caller's transaction keeps assignment stable while the reports are read. */
    public UUID candidateIdForThread(
        UUID threadId,
        UUID viewerId) {
        Map<String, Object> thread = db.one("""
            SELECT t.candidate_user_id
            FROM consultation_threads t
            JOIN users viewer ON viewer.id = ?::uuid
            JOIN users candidate ON candidate.id = t.candidate_user_id
            WHERE t.id = ?::uuid
              AND viewer.status = 'ACTIVE'
              AND candidate.status <> 'DELETED'
              AND (
                  viewer.role IN ('ADMIN', 'MANAGEMENT', 'STAFF')
                  OR viewer.id = t.candidate_user_id
                  OR viewer.id = t.consultant_user_id
              )
            FOR SHARE OF t
            """, viewerId, threadId);

        return (UUID) thread.get("candidate_user_id");
    }

    public List<Map<String, Object>> listResumeAnalyses(
        UUID owner,
        UUID resumeId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS resume_id
            )
            SELECT a.id, a.resume_id, cv.title AS resume_title, a.analysis_type,
                   a.overall_score, a.ats_score, a.summary, a.strengths_json,
                   a.issues_json, a.writing_quality_json, a.model_name, a.created_at,
                   COALESCE((
                       SELECT jsonb_agg(to_jsonb(s) ORDER BY s.created_at DESC, s.id DESC)
                       FROM resume_analysis_suggestions s
                       WHERE s.analysis_id = a.id
                   ), '[]'::jsonb) AS suggestions
            FROM resume_analyses a
            JOIN resumes cv ON cv.id = a.resume_id
            CROSS JOIN filters f
            WHERE cv.user_id = f.owner_id
              AND cv.status = 'READY'
              AND (f.resume_id IS NULL OR a.resume_id = f.resume_id)
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ? OFFSET ?
            """, owner, resumeId, limit, offset);
    }

    public List<Map<String, Object>> listAtsAnalyses(
        UUID owner,
        UUID resumeId,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS resume_id, ?::uuid AS job_id
            )
            SELECT a.id, a.user_id, a.resume_id, cv.title AS resume_title,
                   a.market_job_id, j.job_title, j.company_name,
                   j.status AS job_status, a.match_result_id, a.similarity_score,
                   a.ats_score, a.strengths_json, a.missing_skills_json,
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
            JOIN market_job_posts j ON j.id = a.market_job_id
            CROSS JOIN filters f
            WHERE a.user_id = f.owner_id
              AND cv.status = 'READY'
              AND (f.resume_id IS NULL OR a.resume_id = f.resume_id)
              AND (f.job_id IS NULL OR a.market_job_id = f.job_id)
              AND (
                  a.match_result_id IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM job_match_results result
                      JOIN job_matching_runs run ON run.id = result.matching_run_id
                      WHERE result.id = a.match_result_id
                        AND run.user_id = f.owner_id
                        AND run.status = 'COMPLETED'
                        AND result.market_job_id = a.market_job_id
                  )
              )
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT ? OFFSET ?
            """, owner, resumeId, jobId, limit, offset);
    }

    public List<Map<String, Object>> listMatchingResults(
        UUID owner,
        UUID resumeId,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS resume_id, ?::uuid AS job_id
            )
            SELECT result.id, result.matching_run_id, result.market_job_id,
                   run.basis, run.provider, run.model, run.status AS matching_status,
                   run.started_at, run.completed_at, j.job_title, j.company_name,
                   j.location, j.salary_text, j.status AS job_status,
                   result.vector_similarity_score, result.llm_rerank_score,
                   result.final_score, result.rank_position, result.match_reason_json,
                   result.strengths_json, result.skill_gaps_json, result.created_at,
                   COALESCE((
                       SELECT jsonb_agg(cv.id ORDER BY cv.id)
                       FROM matching_run_resumes link
                       JOIN resumes cv ON cv.id = link.resume_id
                       WHERE link.matching_run_id = run.id
                         AND cv.user_id = run.user_id
                         AND cv.status = 'READY'
                   ), '[]'::jsonb) AS resume_ids
            FROM job_match_results result
            JOIN job_matching_runs run ON run.id = result.matching_run_id
            JOIN market_job_posts j ON j.id = result.market_job_id
            CROSS JOIN filters f
            WHERE run.user_id = f.owner_id
              AND run.status = 'COMPLETED'
              AND (f.job_id IS NULL OR result.market_job_id = f.job_id)
              AND (
                  f.resume_id IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM matching_run_resumes link
                      JOIN resumes cv ON cv.id = link.resume_id
                      WHERE link.matching_run_id = run.id
                        AND cv.user_id = f.owner_id
                        AND cv.status = 'READY'
                        AND cv.id = f.resume_id
                  )
              )
            ORDER BY result.created_at DESC, result.id DESC
            LIMIT ? OFFSET ?
            """, owner, resumeId, jobId, limit, offset);
    }

    public List<Map<String, Object>> listSalaryEstimations(
        UUID owner,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS job_id
            )
            SELECT estimation.id, estimation.user_id, estimation.market_job_id,
                   estimation.target_job_title, j.job_title, j.company_name,
                   j.status AS job_status, estimation.estimated_min,
                   estimation.estimated_max, estimation.currency,
                   estimation.reasoning, estimation.model_name, estimation.created_at
            FROM salary_estimations estimation
            LEFT JOIN market_job_posts j ON j.id = estimation.market_job_id
            CROSS JOIN filters f
            WHERE estimation.user_id = f.owner_id
              AND (f.job_id IS NULL OR estimation.market_job_id = f.job_id)
            ORDER BY estimation.created_at DESC, estimation.id DESC
            LIMIT ? OFFSET ?
            """, owner, jobId, limit, offset);
    }

    public List<Map<String, Object>> listCompletedAssessments(
        UUID owner,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS job_id
            )
            SELECT session.id, session.user_id, session.target_job_title,
                   session.market_job_id, j.job_title, j.company_name,
                   j.status AS job_status, session.job_role_category_snapshot,
                   session.part1_count, session.part2_count, session.skill_scores,
                   session.status, session.is_current, session.reused_from_session_id,
                   session.started_at, session.submitted_at, session.created_at
            FROM assessment_sessions session
            LEFT JOIN market_job_posts j ON j.id = session.market_job_id
            CROSS JOIN filters f
            WHERE session.user_id = f.owner_id
              AND session.status = 'completed'
              AND (f.job_id IS NULL OR session.market_job_id = f.job_id)
              AND (
                  session.reused_from_session_id IS NULL
                  OR EXISTS (
                      SELECT 1
                      FROM assessment_sessions previous
                      WHERE previous.id = session.reused_from_session_id
                        AND previous.user_id = f.owner_id
                  )
              )
            ORDER BY session.created_at DESC, session.id DESC
            LIMIT ? OFFSET ?
            """, owner, jobId, limit, offset);
    }

    public List<Map<String, Object>> listGapAnalyses(
        UUID owner,
        UUID resumeId,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS resume_id, ?::uuid AS job_id
            )
            SELECT gap.id, gap.user_id, gap.market_job_id, j.job_title, j.company_name,
                   j.status AS job_status, gap.resume_id, gap.assessment_session_id,
                   gap.target_job_title, gap.input_source, gap.version, gap.is_latest,
                   gap.summary, gap.status, gap.created_at, gap.completed_at,
                   COALESCE((
                       SELECT jsonb_agg(to_jsonb(skill) ORDER BY skill.urgency_score DESC NULLS LAST, skill.id DESC)
                       FROM gap_analysis_skills skill
                       WHERE skill.gap_analysis_id = gap.id
                   ), '[]'::jsonb) AS skills
            FROM gap_analyses gap
            LEFT JOIN market_job_posts j ON j.id = gap.market_job_id
            CROSS JOIN filters f
            WHERE gap.user_id = f.owner_id
              AND gap.status = 'COMPLETED'
              AND (f.resume_id IS NULL OR gap.resume_id = f.resume_id)
              AND (f.job_id IS NULL OR gap.market_job_id = f.job_id)
              AND (
                  gap.resume_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM resumes cv
                      WHERE cv.id = gap.resume_id AND cv.user_id = f.owner_id
                  )
              )
              AND (
                  gap.assessment_session_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM assessment_sessions session
                      WHERE session.id = gap.assessment_session_id
                        AND session.user_id = f.owner_id
                        AND session.status = 'completed'
                  )
              )
            ORDER BY gap.created_at DESC, gap.id DESC
            LIMIT ? OFFSET ?
            """, owner, resumeId, jobId, limit, offset);
    }

    public List<Map<String, Object>> listRoadmaps(
        UUID owner,
        UUID resumeId,
        UUID jobId,
        int limit,
        int offset) {
        return db.list("""
            WITH filters AS (
                SELECT ?::uuid AS owner_id, ?::uuid AS resume_id, ?::uuid AS job_id
            )
            SELECT roadmap.id, roadmap.user_id, roadmap.market_job_id,
                   j.job_title, j.company_name, j.status AS job_status,
                   roadmap.gap_analysis_id, gap.resume_id, gap.assessment_session_id,
                   gap.version AS gap_version, gap.is_latest AS gap_is_latest,
                   roadmap.target_job_title, roadmap.title, roadmap.estimated_total_hours,
                   roadmap.status, roadmap.is_outdated, roadmap.progress_percent,
                   roadmap.created_at, roadmap.updated_at,
                   COALESCE((
                       SELECT jsonb_agg(
                           to_jsonb(node) || jsonb_build_object(
                               'prerequisite_ids', COALESCE((
                                   SELECT jsonb_agg(
                                       prerequisite.id ORDER BY prerequisite.sequence_order, prerequisite.id
                                   )
                                   FROM roadmap_node_prerequisites link
                                   JOIN roadmap_nodes prerequisite
                                     ON prerequisite.id = link.prerequisite_node_id
                                    AND prerequisite.roadmap_id = node.roadmap_id
                                   WHERE link.node_id = node.id
                               ), '[]'::jsonb),
                               'resources', COALESCE((
                                   SELECT jsonb_agg(
                                       jsonb_build_object(
                                           'id', resource.id,
                                           'title', resource.title,
                                           'resource_type', resource.resource_type,
                                           'provider', resource.provider,
                                           'url', resource.url,
                                           'description', resource.description,
                                           'language', resource.language,
                                           'duration_minutes', resource.duration_minutes,
                                           'is_free', resource.is_free,
                                           'sequence_order', link.sequence_order,
                                           'relevance_score', link.relevance_score,
                                           'recommendation_reason', link.recommendation_reason,
                                           'is_primary', link.is_primary
                                       ) ORDER BY link.sequence_order NULLS LAST, resource.id
                                   )
                                   FROM roadmap_node_resources link
                                   JOIN learning_resources resource
                                     ON resource.id = link.resource_id AND resource.is_active
                                   WHERE link.roadmap_node_id = node.id
                               ), '[]'::jsonb)
                           ) ORDER BY node.sequence_order, node.id
                       )
                       FROM roadmap_nodes node
                       WHERE node.roadmap_id = roadmap.id
                   ), '[]'::jsonb) AS nodes
            FROM roadmaps roadmap
            LEFT JOIN gap_analyses gap
              ON gap.id = roadmap.gap_analysis_id AND gap.user_id = roadmap.user_id
            LEFT JOIN market_job_posts j ON j.id = roadmap.market_job_id
            CROSS JOIN filters f
            WHERE roadmap.user_id = f.owner_id
              AND roadmap.status IN ('ready', 'completed')
              AND (f.resume_id IS NULL OR gap.resume_id = f.resume_id)
              AND (f.job_id IS NULL OR roadmap.market_job_id = f.job_id)
              AND (roadmap.gap_analysis_id IS NULL OR gap.id IS NOT NULL)
              AND (
                  gap.resume_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM resumes cv
                      WHERE cv.id = gap.resume_id AND cv.user_id = f.owner_id
                  )
              )
              AND (
                  gap.assessment_session_id IS NULL
                  OR EXISTS (
                      SELECT 1 FROM assessment_sessions session
                      WHERE session.id = gap.assessment_session_id
                        AND session.user_id = f.owner_id
                        AND session.status = 'completed'
                  )
              )
            ORDER BY roadmap.created_at DESC, roadmap.id DESC
            LIMIT ? OFFSET ?
            """, owner, resumeId, jobId, limit, offset);
    }
}
