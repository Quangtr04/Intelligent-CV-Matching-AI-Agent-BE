package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentAnswerRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapNodeStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class LearningService {

    private final LearningRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final AssessmentService assessments;

    private final ResumeService resumes;

    public LearningService(
        LearningRepository db,
        CurrentUser user,
        AiService ai,
        AssessmentService assessments,
        ResumeService resumes) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.assessments = assessments;
        this.resumes = resumes;
    }

    public Map<String, Object> assess(
        AssessmentRequest request) {
        return assessments.create(request.targetJobTitle(), request.jobId());
    }

    public List<Map<String, Object>> assessments() {
        return db.listAssessmentSessionsByUserId(user.id());
    }

    public Map<String, Object> assessment(
        UUID id) {
        return assessments.get(id);
    }

    public Map<String, Object> answer(
        UUID id,
        AssessmentAnswerRequest answer) {
        return assessments.answer(id, answer.questionId(), answer.selectedOption());
    }

    public Map<String, Object> submit(
        UUID id) {
        return assessments.submit(id);
    }

    @Transactional
    public Map<String, Object> createRoadmap(
        RoadmapRequest request) {
        user.requireRole("CANDIDATE");

        var profile = db.findCandidateProfilesByUserId(user.id()).orElse(Map.of());
        var job = request.jobId() == null ? Map.<String, Object>of() : db.getMarketJobPostsById(request.jobId());
        var cv = request.resumeId() == null ? Map.<String, Object>of() : resumes.owned(request.resumeId());
        if (!cv.isEmpty() && !"READY".equals(cv.get("status")))
            throw ApiException.conflict("Confirm CV data first");

        String target = request.targetJobTitle();

        if (target == null || target.isBlank())
            target = text(job.get("job_title"));

        if (target.isBlank())
            target = text(profile.get("target_job_title"));

        if (target.isBlank())
            throw ApiException.badRequest("Select a target career");

        Map<String, Object> assessment;

        if (request.assessmentId() != null) {
            assessment = db.getAssessmentSessionsById(request.assessmentId(), user.id());
            if (!target.equalsIgnoreCase(text(assessment.get("target_job_title"))))
                throw ApiException.badRequest("Assessment must correspond to the target career");
        } else
            assessment = db.findAssessmentSessionsByUserId(user.id(), target).orElse(Map.of());

        var profileSkills = db.listCandidateProfileSkillsByUserId(user.id());
        var requirements = request.jobId() == null ? List.of() : db.listMarketJobSkillsByMarketJobId(request.jobId());
        var generated = ai.generate(
            "ROADMAP_GENERATE",
            fields(
                "target_job_title",
                target,
                "job",
                job,
                "required_skills",
                requirements,
                "profile",
                profile,
                "profile_skills",
                profileSkills,
                "resume",
                cv,
                "assessment",
                assessment,
                "prerequisite_numbering",
                ("1-based sequence, prerequisites must precede their node; do not " + "invent resource URLs")
            )
        );
        List<?> nodes = array(generated.get("nodes"));
        validateNodes(nodes);

        String title = text(generated.get("title"));
        if (!(generated.get("title") instanceof String) || title.isBlank() || title.length() > 255)
            throw new ApiException(502, "Invalid roadmap title");
        if (!(generated.get("skill_gaps") instanceof List<?>))
            throw new ApiException(502, "Skill gaps must be an array");

        List<?> gaps = array(generated.get("skill_gaps"));
        validateGaps(gaps);

        db.getUsersByIdForUpdate(user.id());
        int version = ((Number) db.readGapAnalysesByUserId(user.id(), target)).intValue();

        db.updateGapAnalysesByUserId(user.id(), target);
        UUID gapId = db.insertGapAnalyses(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                request.jobId(),
                "resume_id",
                request.resumeId(),
                "assessment_session_id",
                assessment.get("id"),
                "target_job_title",
                target,
                "input_source",
                !assessment.isEmpty() ? "ASSESSMENT" : !cv.isEmpty() ? "RESUME" : !job.isEmpty() ? "JOB" : "PROFILE",
                "version",
                version,
                "summary",
                db.json(generated.get("summary")),
                "status",
                "COMPLETED",
                "completed_at",
                OffsetDateTime.now()
            )
        );

        for (Object raw : gaps) {
            var gap = object(raw);

            db.insertGapAnalysisSkills(
                fields(
                    "gap_analysis_id",
                    gapId,
                    "skill_name",
                    gap.get("skill_name"),
                    "gap_status",
                    gap.get("gap_status"),
                    "current_level",
                    gap.get("current_level"),
                    "target_level",
                    gap.get("target_level"),
                    "urgency_score",
                    (int) number(gap.get("urgency_score"), 0),
                    "reasoning",
                    gap.get("reasoning")
                )
            );
        }

        int hours = nodes.stream().mapToInt(n -> (int) number(object(n).get("estimated_hours"), 0)).sum();

        UUID roadmapId = db.insertRoadmaps(
            fields(
                "user_id",
                user.id(),
                "market_job_id",
                request.jobId(),
                "gap_analysis_id",
                gapId,
                "target_job_title",
                target,
                "title",
                title,
                "estimated_total_hours",
                hours,
                "status",
                "ready"
            )
        );

        List<UUID> nodeIds = new ArrayList<>();
        int order = 1;

        for (Object raw : nodes) {
            var node = object(raw);
            String skill = text(node.get("skill_name"));
            UUID skillId = (UUID) db.findSkillsByLower(skill).orElse(Map.of()).get("id");

            UUID nodeId = db.insertRoadmapNodes(
                fields(
                    "roadmap_id",
                    roadmapId,
                    "skill_id",
                    skillId,
                    "skill_name",
                    skill,
                    "description",
                    node.get("description"),
                    "sequence_order",
                    order++,
                    "estimated_hours",
                    (int) number(node.get("estimated_hours"), 0)
                )
            );
            nodeIds.add(nodeId);

            for (var resource : db.listLearningResourcesByIsActive("%" + skill + "%"))
                db.updateRoadmapNodeResources(
                    nodeId,
                    resource.get("id"),
                    1,
                    "Existing catalog resource related to " + skill
                );
        }

        for (int i = 0; i < nodes.size(); i++)
            for (Object prerequisite : array(object(nodes.get(i)).get("prerequisites"))) {
                int sequence = ((Number) prerequisite).intValue();

                db.updateRoadmapNodePrerequisites(nodeIds.get(i), nodeIds.get(sequence - 1));
                db.updateRoadmapNodesById(nodeIds.get(sequence - 1));
            }

        return roadmap(roadmapId);
    }

    public static void validateNodes(
        List<?> nodes) {
        if (nodes.isEmpty() || nodes.size() > 50)
            throw new ApiException(502, "Roadmap must have 1..50 nodes");

        for (int i = 0; i < nodes.size(); i++) {
            var node = object(nodes.get(i));
            String name = text(node.get("skill_name"));
            double hours = number(node.get("estimated_hours"), -1);
            if (!(node.get("skill_name") instanceof String) || name.isBlank() || name.length() > 150
                || !Double.isFinite(hours) || hours < 1 || hours > 1000 || hours != Math.floor(hours))
                throw new ApiException(502, "Invalid roadmap node");

            optionalText(node, "description", 30000);
            if (node.get("prerequisites") != null && !(node.get("prerequisites") instanceof List<?>))
                throw new ApiException(502, "Prerequisites must be an array");

            Set<Integer> seen = new HashSet<>();

            for (Object raw : array(node.get("prerequisites"))) {
                if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1
                    || n.intValue() > i || !seen.add(n.intValue()))
                    throw new ApiException(502, "Prerequisites must reference unique earlier nodes");
            }
        }
    }

    public static void validateGaps(
        List<?> gaps) {
        if (gaps.size() > 100)
            throw new ApiException(502, "Too many skill gaps");

        for (Object raw : gaps) {
            var gap = object(raw);
            if (!(gap.get("skill_name") instanceof String) || text(gap.get("skill_name")).isBlank()
                || text(gap.get("skill_name")).length() > 150 || !(gap.get("gap_status") instanceof String)
                || text(gap.get("gap_status")).isBlank() || text(gap.get("gap_status")).length() > 20)
                throw new ApiException(502, "Invalid skill gap");

            optionalText(gap, "current_level", 20);
            optionalText(gap, "target_level", 20);
            optionalText(gap, "reasoning", 30000);
            FlowSupport.score(gap.get("urgency_score"), 100, "gap urgency");
        }
    }

    private static void optionalText(
        Map<String, Object> value,
        String key,
        int max) {
        Object field = value.get(key);
        if (field != null && (!(field instanceof String) || text(field).length() > max))
            throw new ApiException(502, "Invalid roadmap " + key);
    }

    public List<Map<String, Object>> roadmaps() {
        return db.listRoadmapsByUserId(user.id());
    }

    public Map<String, Object> roadmap(
        UUID id) {
        var roadmap = db.getRoadmapsById(id, user.id());
        var nodes = db.listRoadmapNodesByRoadmapId(id);

        for (var node : nodes) {
            node.put("prerequisites", db.listRoadmapNodePrerequisitesByNodeId(node.get("id")));
            node.put("resources", db.listRoadmapNodeResourcesByRoadmapNodeId(node.get("id")));
        }

        roadmap.put("nodes", nodes);
        roadmap.put("skill_gaps", db.listGapAnalysisSkillsByGapAnalysisId(roadmap.get("gap_analysis_id")));

        return roadmap;
    }

    @Transactional
    public Map<String, Object> node(
        UUID id,
        UUID nodeId,
        RoadmapNodeStatusRequest input) {
        db.getRoadmapsByIdForUpdate(id, user.id());
        db.getRoadmapNodesById(nodeId, id);

        if (!input.status().equals("not_started")) {
            long remaining = ((Number) db.countRoadmapNodePrerequisitesByNodeId(nodeId)).longValue();
            if (remaining > 0)
                throw ApiException.conflict("Complete prerequisite nodes first");
        } else {
            long active = ((Number) db.countRoadmapNodePrerequisitesByPrerequisiteNodeId(nodeId)).longValue();
            if (active > 0)
                throw ApiException.conflict("Reset dependent nodes first");
        }

        if (!input.status().equals("completed")) {
            long active = ((Number) db.countRoadmapNodePrerequisitesByPrerequisiteNodeId(nodeId)).longValue();
            if (active > 0)
                throw ApiException.conflict("Reset dependent nodes first");
        }

        db.updateRoadmapNodesById2(
            input.status(),
            input.status().equals("completed") ? OffsetDateTime.now() : null,
            nodeId
        );
        db.updateRoadmapsByStatus(id, id, id);

        return roadmap(id);
    }
}
