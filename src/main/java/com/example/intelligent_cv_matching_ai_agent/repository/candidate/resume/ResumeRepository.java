package com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by ResumeService. */
@Repository
public class ResumeRepository {

    private final SqlStore db;

    public ResumeRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listResumeTemplatesByIsActive(
        Object... args) {
        return db.list("SELECT * FROM resume_templates WHERE is_active=true ORDER BY name", args);
    }

    public List<Map<String, Object>> listResumesByUserId(
        Object... args) {
        return db.list(
            ("SELECT id,title,source,status,is_primary,language,file_name,file_url,"
                + "created_at,updated_at FROM resumes WHERE user_id=? AND "
                + "status<>'ARCHIVED' ORDER BY is_primary DESC,updated_at DESC"),
            args
        );
    }

    public Map<String, Object> getResumesById(
        Object... args) {
        return db.one("SELECT * FROM resumes WHERE id=?", args);
    }

    public List<Map<String, Object>> listResumeExperiencesByResumeId(
        Object... args) {
        return db.list(
            ("SELECT * FROM resume_experiences WHERE resume_id=? ORDER BY " + "start_date DESC NULLS LAST"),
            args
        );
    }

    public List<Map<String, Object>> listResumeEducationsByResumeId(
        Object... args) {
        return db
            .list(("SELECT * FROM resume_educations WHERE resume_id=? ORDER BY start_date " + "DESC NULLS LAST"), args);
    }

    public List<Map<String, Object>> listResumeProjectsByResumeId(
        Object... args) {
        return db.list("SELECT * FROM resume_projects WHERE resume_id=?", args);
    }

    public List<Map<String, Object>> listResumeCertificationsByResumeId(
        Object... args) {
        return db.list("SELECT * FROM resume_certifications WHERE resume_id=?", args);
    }

    public List<Map<String, Object>> listResumeSkillsByResumeId(
        Object... args) {
        return db.list(
            ("SELECT rs.*,s.name FROM resume_skills rs JOIN skills s ON " + "s.id=rs.skill_id WHERE rs.resume_id=?"),
            args
        );
    }

    public List<Map<String, Object>> listResumeParseResultsByResumeId(
        Object... args) {
        return db.list("SELECT * FROM resume_parse_results WHERE resume_id=? ORDER BY created_at DESC", args);
    }

    public UUID insertResumes(
        Map<String, Object> fields) {
        return db.insert("resumes", fields);
    }

    public UUID insertResumeParseResults(
        Map<String, Object> fields) {
        return db.insert("resume_parse_results", fields);
    }

    public Map<String, Object> getResumeTemplatesById(
        Object... args) {
        return db
            .one("SELECT id,allowed_tier_codes FROM resume_templates WHERE id=? AND is_active=true FOR SHARE", args);
    }

    public Optional<Map<String, Object>> findEffectiveSubscriptionTierCode(
        UUID userId) {
        return db.optional(
            "SELECT t.tier_code FROM user_subscriptions s JOIN subscription_tiers t ON t.id=s.tier_id "
                + "WHERE s.user_id=? AND s.status='ACTIVE' AND (s.expires_at IS NULL OR s.expires_at>now()) "
                + "ORDER BY t.price_monthly DESC,s.started_at DESC LIMIT 1",
            userId
        );
    }

    public Optional<Map<String, Object>> findResumeTemplateById(
        Object... args) {
        return db.optional("SELECT * FROM resume_templates WHERE id=?", args);
    }

    public int updateResumeTemplateById(
        Object... args) {
        return db.update("UPDATE resumes SET template_id=?,updated_at=now() WHERE id=?", args);
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public int updateResumesById(
        Object... args) {
        return db.update(
            ("UPDATE resumes SET title=?,language=?,raw_content=?,structured_data=?,"
                + "content_hash=?,status=?,is_primary=CASE WHEN ? THEN false ELSE "
                + "is_primary END,updated_at=now() WHERE id=?"),
            args
        );
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? FOR UPDATE", args);
    }

    public int updateResumesByUserId(
        Object... args) {
        return db.update(
            ("UPDATE resumes SET is_primary=false,updated_at=now() WHERE user_id=? " + "AND is_primary=true"),
            args
        );
    }

    public int updateResumesById2(
        Object... args) {
        return db.update(
            ("UPDATE resumes SET structured_data=?,raw_content=?,content_hash=?,"
                + "status='READY',is_primary=?,updated_at=now() WHERE id=?"),
            args
        );
    }

    public int updateResumesByUserId2(
        Object... args) {
        return db.update("UPDATE resumes SET is_primary=false WHERE user_id=? AND is_primary=true", args);
    }

    public int updateResumesById3(
        Object... args) {
        return db.update("UPDATE resumes SET is_primary=true,updated_at=now() WHERE id=?", args);
    }

    public int updateResumesById4(
        Object... args) {
        return db.update("UPDATE resumes SET status='ARCHIVED',is_primary=false,updated_at=now() WHERE id=?", args);
    }

    public UUID insertResumeAnalyses(
        Map<String, Object> fields) {
        return db.insert("resume_analyses", fields);
    }

    public Optional<Map<String, Object>> findResumeAnalysisByInput(
        Object... args) {
        return db.optional(
            "SELECT a.id FROM resume_analyses a JOIN resumes r ON r.id=a.resume_id "
                + "WHERE a.resume_id=? AND a.analysis_type=? AND a.input_hash=? "
                + "AND r.user_id=? AND r.status='READY' ORDER BY a.created_at DESC,a.id DESC LIMIT 1",
            args
        );
    }

    public UUID insertResumeAnalysisSuggestions(
        Map<String, Object> fields) {
        return db.insert("resume_analysis_suggestions", fields);
    }

    public List<Map<String, Object>> listResumeAnalysesByResumeId(
        Object... args) {
        return db.list("SELECT * FROM resume_analyses WHERE resume_id=? ORDER BY created_at DESC", args);
    }

    public Map<String, Object> getResumeAnalysesById(
        Object... args) {
        return db.one("SELECT * FROM resume_analyses WHERE id=? AND resume_id=?", args);
    }

    public List<Map<String, Object>> listResumeAnalysisSuggestionsByAnalysisId(
        Object... args) {
        return db.list("SELECT * FROM resume_analysis_suggestions WHERE analysis_id=?", args);
    }

    public Optional<Map<String, Object>> findResumeEmbeddingsByResumeId(
        Object... args) {
        return db.optional(
            ("SELECT id,resume_id,provider,model,content_hash,is_current,created_at "
                + "FROM resume_embeddings WHERE resume_id=? AND provider=? AND model=? "
                + "AND content_hash=? AND is_current=true AND embedding IS NOT NULL"),
            args
        );
    }

    public UUID insertVectorIndexingJobs(
        Map<String, Object> fields) {
        return db.insert("vector_indexing_jobs", fields);
    }

    public Map<String, Object> getResumesByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM resumes WHERE id=? FOR UPDATE", args);
    }

    public int updateVectorIndexingJobsById(
        Object... args) {
        return db.update(
            ("UPDATE vector_indexing_jobs SET status='FAILED',error_message=?," + "completed_at=now() WHERE id=?"),
            args
        );
    }

    public int updateResumeEmbeddingsByResumeId(
        Object... args) {
        return db.update("UPDATE resume_embeddings SET is_current=false WHERE resume_id=?", args);
    }

    public Object readResumeEmbeddings(
        Object... args) {
        return db.scalar(
            ("INSERT INTO resume_embeddings(resume_id,provider,model,embedding,"
                + "content_hash,is_current) VALUES (?,?,?,?::vector,?,true) ON "
                + "CONFLICT(resume_id,provider,model,content_hash) DO UPDATE SET "
                + "embedding=EXCLUDED.embedding,is_current=true RETURNING id"),
            args
        );
    }

    public int updateVectorIndexingJobsById2(
        Object... args) {
        return db.update("UPDATE vector_indexing_jobs SET status='COMPLETED',completed_at=now() WHERE id=?", args);
    }

    public Map<String, Object> getResumeEmbeddingsById(
        Object... args) {
        return db.one(
            ("SELECT id,resume_id,provider,model,content_hash,is_current,created_at "
                + "FROM resume_embeddings WHERE id=?"),
            args
        );
    }

    public int deleteResumeSkillsByResumeId(
        Object... args) {
        return db.update("DELETE FROM resume_skills WHERE resume_id=?", args);
    }

    /**
     * Section names are application keys, never SQL identifiers supplied by a
     * caller.
     */
    public int deleteSection(
        String section,
        UUID resumeId) {
        return switch (section) {
            case "experience" -> db.update("DELETE FROM resume_experiences WHERE resume_id=?", resumeId);
            case "education" -> db.update("DELETE FROM resume_educations WHERE resume_id=?", resumeId);
            case "projects" -> db.update("DELETE FROM resume_projects WHERE resume_id=?", resumeId);
            case "certifications" -> db.update("DELETE FROM resume_certifications WHERE resume_id=?", resumeId);
            default -> throw new IllegalArgumentException("Unsupported CV section: " + section);
        };
    }

    public UUID insertSection(
        String section,
        Map<String, Object> fields) {
        return switch (section) {
            case "experience" -> db.insert("resume_experiences", fields);
            case "education" -> db.insert("resume_educations", fields);
            case "projects" -> db.insert("resume_projects", fields);
            case "certifications" -> db.insert("resume_certifications", fields);
            default -> throw new IllegalArgumentException("Unsupported CV section: " + section);
        };
    }

    public Map<String, Object> getSkillsById(
        Object... args) {
        return db.one("SELECT id FROM skills WHERE id=? AND is_active=true", args);
    }

    public Object readSkills(
        Object... args) {
        return db.scalar(
            ("INSERT INTO skills(name,slug) VALUES (?,?) ON CONFLICT(slug) DO "
                + "UPDATE SET name=skills.name RETURNING id"),
            args
        );
    }

    public int updateResumeSkills(
        Object... args) {
        return db.update(
            ("INSERT INTO resume_skills(resume_id,skill_id,proficiency_level,"
                + "years_experience,source) VALUES (?,?,?,?,?)"),
            args
        );
    }

    public int updateRoadmapsByGapAnalysisId(
        Object... args) {
        return db.update(
            ("UPDATE roadmaps SET is_outdated=true,updated_at=now() WHERE "
                + "gap_analysis_id IN (SELECT id FROM gap_analyses WHERE resume_id=?)"),
            args
        );
    }

}
