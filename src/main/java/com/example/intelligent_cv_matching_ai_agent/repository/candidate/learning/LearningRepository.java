package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by LearningService. */
@Repository
public class LearningRepository {

    private final SqlStore db;

    public LearningRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listAssessmentSessionsByUserId(
        Object... args) {
        return db
            .list(("SELECT * FROM assessment_sessions WHERE user_id=? ORDER BY created_at " + "DESC LIMIT 100"), args);
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one("SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE'", args);
    }

    public Map<String, Object> getAssessmentSessionsById(
        Object... args) {
        return db.one("SELECT * FROM assessment_sessions WHERE id=? AND user_id=? AND status='completed'", args);
    }

    public Optional<Map<String, Object>> findAssessmentSessionsByUserId(
        Object... args) {
        return db.optional(
            ("SELECT * FROM assessment_sessions WHERE user_id=? AND "
                + "target_job_title=? AND status='completed' AND is_current ORDER BY " + "submitted_at DESC LIMIT 1"),
            args
        );
    }

    public List<Map<String, Object>> listCandidateProfileSkillsByUserId(
        Object... args) {
        return db.list(
            ("SELECT s.name,ps.proficiency_level FROM candidate_profile_skills ps "
                + "JOIN skills s ON s.id=ps.skill_id WHERE ps.user_id=?"),
            args
        );
    }

    public List<Map<String, Object>> listMarketJobSkillsByMarketJobId(
        Object... args) {
        return db.list("SELECT skill_name,is_required FROM market_job_skills WHERE market_job_id=?", args);
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? FOR UPDATE", args);
    }

    public Object readGapAnalysesByUserId(
        Object... args) {
        return db.scalar(
            ("SELECT COALESCE(max(version),0)+1 FROM gap_analyses WHERE user_id=? " + "AND target_job_title=?"),
            args
        );
    }

    public int updateGapAnalysesByUserId(
        Object... args) {
        return db.update("UPDATE gap_analyses SET is_latest=false WHERE user_id=? AND target_job_title=?", args);
    }

    public UUID insertGapAnalyses(
        Map<String, Object> fields) {
        return db.insert("gap_analyses", fields);
    }

    public UUID insertGapAnalysisSkills(
        Map<String, Object> fields) {
        return db.insert("gap_analysis_skills", fields);
    }

    public UUID insertRoadmaps(
        Map<String, Object> fields) {
        return db.insert("roadmaps", fields);
    }

    public Optional<Map<String, Object>> findSkillsByLower(
        Object... args) {
        return db.optional("SELECT id FROM skills WHERE lower(name)=lower(?) AND is_active", args);
    }

    public UUID insertRoadmapNodes(
        Map<String, Object> fields) {
        return db.insert("roadmap_nodes", fields);
    }

    public List<Map<String, Object>> listLearningResourcesByIsActive(
        Object... args) {
        return db.list(
            ("SELECT id FROM learning_resources WHERE is_active AND title ILIKE ? "
                + "ORDER BY is_free DESC,title LIMIT 3"),
            args
        );
    }

    public int updateRoadmapNodeResources(
        Object... args) {
        return db.update(
            ("INSERT INTO roadmap_node_resources(roadmap_node_id,resource_id,"
                + "sequence_order,recommendation_reason) VALUES(?,?,?,?)"),
            args
        );
    }

    public int updateRoadmapNodePrerequisites(
        Object... args) {
        return db.update("INSERT INTO roadmap_node_prerequisites(node_id,prerequisite_node_id) VALUES(?,?)", args);
    }

    public int updateRoadmapNodesById(
        Object... args) {
        return db.update("UPDATE roadmap_nodes SET is_prerequisite=true WHERE id=?", args);
    }

    public List<Map<String, Object>> listRoadmapsByUserId(
        Object... args) {
        return db.list("SELECT * FROM roadmaps WHERE user_id=? ORDER BY created_at DESC LIMIT 100", args);
    }

    public Map<String, Object> getRoadmapsById(
        Object... args) {
        return db.one("SELECT * FROM roadmaps WHERE id=? AND user_id=?", args);
    }

    public List<Map<String, Object>> listRoadmapNodesByRoadmapId(
        Object... args) {
        return db.list("SELECT * FROM roadmap_nodes WHERE roadmap_id=? ORDER BY sequence_order", args);
    }

    public List<Map<String, Object>> listRoadmapNodePrerequisitesByNodeId(
        Object... args) {
        return db.list("SELECT prerequisite_node_id FROM roadmap_node_prerequisites WHERE node_id=?", args);
    }

    public List<Map<String, Object>> listRoadmapNodeResourcesByRoadmapNodeId(
        Object... args) {
        return db.list(
            ("SELECT r.*,nr.recommendation_reason FROM roadmap_node_resources nr "
                + "JOIN learning_resources r ON r.id=nr.resource_id WHERE "
                + "nr.roadmap_node_id=? AND r.is_active ORDER BY nr.sequence_order"),
            args
        );
    }

    public List<Map<String, Object>> listGapAnalysisSkillsByGapAnalysisId(
        Object... args) {
        return db.list("SELECT * FROM gap_analysis_skills WHERE gap_analysis_id=?", args);
    }

    public Map<String, Object> getRoadmapsByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM roadmaps WHERE id=? AND user_id=? FOR UPDATE", args);
    }

    public Map<String, Object> getRoadmapNodesById(
        Object... args) {
        return db.one("SELECT id FROM roadmap_nodes WHERE id=? AND roadmap_id=?", args);
    }

    public Object countRoadmapNodePrerequisitesByNodeId(
        Object... args) {
        return db.scalar(
            ("SELECT count(*) FROM roadmap_node_prerequisites p JOIN roadmap_nodes "
                + "n ON n.id=p.prerequisite_node_id WHERE p.node_id=? AND " + "n.status<>'completed'"),
            args
        );
    }

    public Object countRoadmapNodePrerequisitesByPrerequisiteNodeId(
        Object... args) {
        return db.scalar(
            ("SELECT count(*) FROM roadmap_node_prerequisites p JOIN roadmap_nodes "
                + "n ON n.id=p.node_id WHERE p.prerequisite_node_id=? AND " + "n.status<>'not_started'"),
            args
        );
    }

    public int updateRoadmapNodesById2(
        Object... args) {
        return db.update("UPDATE roadmap_nodes SET status=?,completed_at=? WHERE id=?", args);
    }

    public int updateRoadmapsByStatus(
        Object... args) {
        return db.update(
            ("UPDATE roadmaps SET progress_percent=(SELECT floor(100.0*count(*) "
                + "FILTER(WHERE status='completed')/NULLIF(count(*),0)) FROM "
                + "roadmap_nodes WHERE roadmap_id=?),status=CASE WHEN NOT EXISTS(SELECT "
                + "1 FROM roadmap_nodes WHERE roadmap_id=? AND status<>'completed') THEN "
                + "'completed' ELSE 'ready' END,updated_at=now() WHERE id=?"),
            args
        );
    }

}
