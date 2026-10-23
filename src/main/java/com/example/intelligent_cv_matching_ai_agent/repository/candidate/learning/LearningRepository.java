package com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** SQL persistence operations used by LearningService. */
@Repository
public class LearningRepository {

    private static final String RECOMMENDABLE_RESOURCE =
        "r.is_active AND ((r.source_type<>'ADMIN_CURATED' AND NOT EXISTS ("
            + "SELECT 1 FROM rag_course_links linked WHERE linked.learning_resource_id=r.id)) OR EXISTS ("
            + "SELECT 1 FROM rag_course_links c JOIN rag_documents d ON d.id=c.rag_document_id "
            + "WHERE c.learning_resource_id=r.id AND c.status='APPROVED' "
            + "AND COALESCE(d.metadata->>'disabled','false')<>'true'))";

    private static final String RESOURCE_FIELDS =
        "r.id,r.title,r.resource_type,r.provider,r.url,r.is_free,r.metadata_json";

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
        return db.one(
            "SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE' "
                + "AND (valid_through IS NULL OR valid_through>now())",
            args
        );
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

    public Optional<Map<String, Object>> findCompletedAssessmentForJob(
        UUID userId,
        UUID jobId) {
        return db.optional(
            "SELECT * FROM assessment_sessions WHERE user_id=? AND market_job_id=? "
                + "AND status='completed' ORDER BY submitted_at DESC,created_at DESC LIMIT 1",
            userId,
            jobId
        );
    }

    public List<Map<String, Object>> listResumeSkillsByResumeId(
        UUID resumeId) {
        return db.list(
            "SELECT s.name FROM resume_skills rs JOIN skills s ON s.id=rs.skill_id WHERE rs.resume_id=?",
            resumeId
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
            ("SELECT COALESCE(max(version),0)+1 FROM gap_analyses WHERE user_id=? " + "AND market_job_id=?"),
            args
        );
    }

    public int updateGapAnalysesByUserId(
        Object... args) {
        return db.update("UPDATE gap_analyses SET is_latest=false WHERE user_id=? AND market_job_id=?", args);
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
            "SELECT r.id FROM learning_resources r WHERE " + RECOMMENDABLE_RESOURCE
                + " AND r.title ILIKE ? ORDER BY (r.source_type='ADMIN_CURATED') DESC,r.is_free DESC,r.title LIMIT 3",
            args
        );
    }

    public List<Map<String, Object>> listLearningResourcesForSkill(
        String skillSlug,
        String titlePattern) {
        return db.list(
            "SELECT " + RESOURCE_FIELDS + " FROM learning_resources r WHERE " + RECOMMENDABLE_RESOURCE
                + " AND (jsonb_exists(r.metadata_json->'skill_slugs', ?) "
                + "OR (r.metadata_json->'skill_slugs' IS NULL AND r.title ILIKE ?)) "
                + "ORDER BY (r.source_type='ADMIN_CURATED') DESC,r.is_free DESC,r.title LIMIT 5",
            skillSlug,
            titlePattern
        );
    }

    public List<Map<String, Object>> listApprovedCourseResources(
        Collection<String> skillSlugs,
        String targetSlug) {
        List<Object> args = new ArrayList<>(skillSlugs);
        args.add(targetSlug);
        return db.list(
            "SELECT " + RESOURCE_FIELDS + " FROM learning_resources r WHERE " + RECOMMENDABLE_RESOURCE
                + " AND r.source_type='ADMIN_CURATED' AND (" + "jsonb_exists_any(r.metadata_json->'skill_slugs', ARRAY["
                + String.join(",", Collections.nCopies(skillSlugs.size(), "?")) + "]::text[]) "
                + "OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(r.metadata_json->'skill_slugs') skill(slug) "
                + "WHERE skill.slug<>'' AND position('-'||skill.slug||'-' IN '-'||?||'-')>0)) "
                + "ORDER BY r.is_free DESC,r.title LIMIT 15",
            args.toArray()
        );
    }

    public List<Map<String, Object>> listLearningResourcesByUrls(
        Collection<String> urls) {
        if (urls.isEmpty()) {
            return List.of();
        }
        return db.list(
            "SELECT " + RESOURCE_FIELDS + " FROM learning_resources r WHERE " + RECOMMENDABLE_RESOURCE
                + " AND r.url IN (" + String.join(",", Collections.nCopies(urls.size(), "?"))
                + ") ORDER BY (r.source_type='ADMIN_CURATED') DESC,r.is_free DESC,r.title",
            urls.toArray()
        );
    }

    public int updateRoadmapNodeResources(
        UUID nodeId,
        UUID resourceId,
        int sequenceOrder,
        String reason,
        Map<String, Object> resource) {
        return db.update(
            "INSERT INTO roadmap_node_resources(roadmap_node_id,resource_id,sequence_order,recommendation_reason,"
                + "resource_snapshot) VALUES(?,?,?,?,"
                + "(SELECT to_jsonb(r)||?::jsonb FROM learning_resources r WHERE r.id=?))",
            nodeId,
            resourceId,
            sequenceOrder,
            reason,
            db.json(resource),
            resourceId
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
        List<Map<String, Object>> rows = db.list(
            "SELECT nr.resource_id,COALESCE(nr.resource_snapshot,to_jsonb(r)) AS resource_snapshot,"
                + "nr.recommendation_reason FROM roadmap_node_resources nr "
                + "JOIN learning_resources r ON r.id=nr.resource_id WHERE nr.roadmap_node_id=? "
                + "ORDER BY nr.sequence_order",
            args
        );
        return rows.stream().map(row -> {
            Map<String, Object> resource = new LinkedHashMap<>(object(row.get("resource_snapshot")));
            resource.put("id", row.get("resource_id"));
            resource.put("recommendation_reason", row.get("recommendation_reason"));
            return resource;
        }).toList();
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
