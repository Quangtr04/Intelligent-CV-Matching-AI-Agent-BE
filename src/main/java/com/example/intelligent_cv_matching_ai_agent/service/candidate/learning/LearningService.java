package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentAnswerRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapNodeStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.RoadmapValidator;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.MissingSkillAnalysis;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.learning.LearningReferenceService;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class LearningService {

    private final LearningRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final AssessmentService assessments;

    private final ResumeService resumes;

    private final LearningReferenceService references;

    private final TransactionTemplate transactions;

    private final TransactionTemplate modelCalls;

    public LearningService(
        LearningRepository db,
        CurrentUser user,
        AiService ai,
        AssessmentService assessments,
        ResumeService resumes,
        LearningReferenceService references,
        PlatformTransactionManager transactionManager) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.assessments = assessments;
        this.resumes = resumes;
        this.references = references;
        this.transactions = new TransactionTemplate(transactionManager);
        this.modelCalls = new TransactionTemplate(transactionManager);
        this.modelCalls.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public Map<String, Object> assess(
        AssessmentRequest request) {
        return assessments.create(request.targetJobTitle(), request.jobId());
    }

    public Map<String, Object> recommendations(
        String target,
        List<String> skills) {
        user.requireRole("CANDIDATE");
        if (target == null || target.isBlank() || target.length() > 255) {
            throw ApiException.badRequest("Select a target career");
        }
        List<String> names = skills == null ? List.of() : skills;
        if (names.size() > 50 || names.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 150)) {
            throw ApiException.badRequest("Provide up to 50 valid skill names");
        }
        return references.recommendSkills(names);
    }

    public List<Map<String, Object>> assessments() {
        return db.listAssessmentSessionsByUserId(user.id());
    }

    public Map<String, Object> assessment(
        UUID id) {
        return withAssessmentRecommendations(assessments.get(id));
    }

    public Map<String, Object> answer(
        UUID id,
        AssessmentAnswerRequest answer) {
        return assessments.answer(id, answer.questionId(), answer.selectedOption());
    }

    public Map<String, Object> submit(
        UUID id) {
        return withAssessmentRecommendations(assessments.submit(id));
    }

    private Map<String, Object> withAssessmentRecommendations(
        Map<String, Object> session) {
        if (!"completed".equals(session.get("status"))) {
            return session;
        }
        Map<String, Object> result = new LinkedHashMap<>(session);
        result.put("learning_recommendations", references.forAssessment(session));
        return result;
    }

    public Map<String, Object> createRoadmap(
        RoadmapRequest request) {
        user.requireRole("CANDIDATE");
        PreparedRoadmap prepared = transactions.execute(status -> prepareRoadmap(request));
        Map<String, Object> generated = prepared.missing().isEmpty()
            ? fields(
                "title",
                "Missing JD skills",
                "summary",
                fields("overview", "No missing required skills were identified from the available evidence."),
                "skill_gaps",
                List.of(),
                "nodes",
                List.of()
            )
            : new LinkedHashMap<>(modelCalls.execute(status -> ai.generate("ROADMAP_GENERATE", prepared.input())));
        return transactions.execute(status -> publishRoadmap(prepared, generated));
    }

    private PreparedRoadmap prepareRoadmap(
        RoadmapRequest request) {

        if (request.jobId() == null) {
            throw ApiException.badRequest("Select a JD to identify its missing skills");
        }
        var profile = db.findCandidateProfilesByUserId(user.id()).orElse(Map.of());
        var job = db.getMarketJobPostsById(request.jobId());
        var cv = request.resumeId() == null ? Map.<String, Object>of() : resumes.owned(request.resumeId());
        if (!cv.isEmpty() && !"READY".equals(cv.get("status")))
            throw ApiException.conflict("Confirm CV data first");

        String target = text(job.get("job_title"));

        if (target.isBlank())
            throw ApiException.badRequest("Select a target career");

        Map<String, Object> assessment;

        if (request.assessmentId() != null) {
            assessment = db.getAssessmentSessionsById(request.assessmentId(), user.id());
            if (!request.jobId().toString().equals(text(assessment.get("market_job_id")))) {
                throw ApiException.badRequest("Assessment must be completed for the same selected JD");
            }
        } else
            assessment = db.findCompletedAssessmentForJob(user.id(), request.jobId()).orElse(Map.of());

        var profileSkills = db.listCandidateProfileSkillsByUserId(user.id());
        List<Map<String, Object>> requirements = db.listMarketJobSkillsByMarketJobId(request.jobId());
        var resumeSkills = request.resumeId() == null
            ? List.<Map<String, Object>>of()
            : db.listResumeSkillsByResumeId(request.resumeId());
        var missing = MissingSkillAnalysis.find(requirements, profileSkills, resumeSkills, assessment);
        var recommendationSnapshot = missingSkillReferences(missing);
        Map<String, Object> input = fields(
            "learning_scope",
            MissingSkillAnalysis.SCOPE,
            "allowed_missing_skills",
            missing,
            "target_job_title",
            target,
            "jd_text",
            compactRoadmapJd(job),
            "profile",
            fields("years_experience", profile.get("years_experience")),
            "prerequisite_numbering",
            "1-based sequence; prerequisites must precede their node. Resource URLs are attached by the server."
        );
        return new PreparedRoadmap(
            user.id(),
            request,
            target,
            assessment,
            !assessment.isEmpty() ? "ASSESSMENT" : !cv.isEmpty() ? "RESUME" : "JOB",
            missing,
            recommendationSnapshot,
            input
        );
    }

    private String compactRoadmapJd(
        Map<String, Object> job) {
        StringBuilder result = new StringBuilder();
        for (String key : List
            .of("parsed_requirements", "parsed_responsibilities", "parsed_description", "raw_content")) {
            String source = text(job.get(key)).strip();
            if (!source.isBlank() && result.indexOf(source) < 0) {
                int available = Math.min(1200, 4800 - result.length());
                if (available <= 0) {
                    break;
                }
                if (!result.isEmpty()) {
                    result.append('\n');
                    available--;
                }
                result.append(source, 0, Math.min(source.length(), available));
            }
        }
        return result.toString();
    }

    private Map<String, Object> publishRoadmap(
        PreparedRoadmap prepared,
        Map<String, Object> generated) {
        RoadmapRequest request = prepared.request();
        UUID owner = prepared.owner();
        String target = prepared.target();
        Map<String, Object> assessment = prepared.assessment();
        List<Map<String, Object>> missing = prepared.missing();
        Map<String, Object> recommendationSnapshot = prepared.recommendations();
        if (!missing.isEmpty()) {
            MissingSkillAnalysis.constrainGenerated(generated, missing);
        }
        List<?> nodes = array(generated.get("nodes"));

        String title = text(generated.get("title"));
        if (!(generated.get("title") instanceof String) || title.isBlank() || title.length() > 255)
            throw new ApiException(502, "Invalid roadmap title");
        if (!(generated.get("skill_gaps") instanceof List<?>))
            throw new ApiException(502, "Skill gaps must be an array");

        List<?> gaps = array(generated.get("skill_gaps"));
        validateGaps(gaps);

        db.getUsersByIdForUpdate(owner);
        // Inference can take minutes. Do not publish against a JD/CV/assessment that became unavailable meanwhile.
        db.getMarketJobPostsById(request.jobId());
        if (request.resumeId() != null && !"READY".equals(resumes.owned(request.resumeId()).get("status"))) {
            throw ApiException.conflict("Confirm CV data first");
        }
        if (assessment.get("id") != null) {
            db.getAssessmentSessionsById(assessment.get("id"), owner);
        }
        int version = ((Number) db.readGapAnalysesByUserId(owner, request.jobId())).intValue();

        db.updateGapAnalysesByUserId(owner, request.jobId());
        UUID gapId = db.insertGapAnalyses(
            fields(
                "user_id",
                owner,
                "market_job_id",
                request.jobId(),
                "resume_id",
                request.resumeId(),
                "assessment_session_id",
                assessment.get("id"),
                "target_job_title",
                target,
                "input_source",
                prepared.inputSource(),
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
                owner,
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
                missing.isEmpty() ? "completed" : "ready",
                "progress_percent",
                missing.isEmpty() ? 100 : 0,
                "learning_recommendations_snapshot",
                db.json(recommendationSnapshot)
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

            int resourceOrder = 1;
            Set<UUID> attachedResources = new HashSet<>();
            for (var resource : resourcesForMissingSkill(recommendationSnapshot, skill)) {
                if (!(resource.get("id") instanceof UUID resourceId) || !attachedResources.add(resourceId)) {
                    continue;
                }
                db.updateRoadmapNodeResources(
                    nodeId,
                    resourceId,
                    resourceOrder++,
                    "Catalog resource from " + text(resource.get("provider")) + " related to " + skill,
                    resource
                );
            }
        }

        for (int i = 0; i < nodes.size(); i++)
            for (Object prerequisite : array(object(nodes.get(i)).get("prerequisites"))) {
                int sequence = ((Number) prerequisite).intValue();

                db.updateRoadmapNodePrerequisites(nodeIds.get(i), nodeIds.get(sequence - 1));
                db.updateRoadmapNodesById(nodeIds.get(sequence - 1));
            }

        return roadmap(roadmapId);
    }

    private record PreparedRoadmap(
        UUID owner,
        RoadmapRequest request,
        String target,
        Map<String, Object> assessment,
        String inputSource,
        List<Map<String, Object>> missing,
        Map<String, Object> recommendations,
        Map<String, Object> input
    ) {
    }

    public static void validateNodes(
        List<?> nodes) {
        RoadmapValidator.validateNodes(nodes);
    }

    public static void validateGaps(
        List<?> gaps) {
        RoadmapValidator.validateGaps(gaps);
    }

    public List<Map<String, Object>> roadmaps() {
        List<Map<String, Object>> roadmaps = db.listRoadmapsByUserId(user.id());
        roadmaps.forEach(roadmap -> {
            var snapshot = object(roadmap.remove("learning_recommendations_snapshot"));
            roadmap.put(
                "learning_scope",
                MissingSkillAnalysis.SCOPE.equals(snapshot.get("learning_scope"))
                    ? MissingSkillAnalysis.SCOPE
                    : "LEGACY_FULL_ROADMAP"
            );
        });
        return roadmaps;
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
        var gaps = db.listGapAnalysisSkillsByGapAnalysisId(roadmap.get("gap_analysis_id"));
        roadmap.put("skill_gaps", gaps);
        Object snapshot = roadmap.remove("learning_recommendations_snapshot");
        boolean targeted = MissingSkillAnalysis.SCOPE.equals(object(snapshot).get("learning_scope"));
        roadmap.put("learning_scope", targeted ? MissingSkillAnalysis.SCOPE : "LEGACY_FULL_ROADMAP");
        if (targeted) {
            List<Map<String, Object>> missing = new ArrayList<>();
            for (Object raw : array(object(snapshot).get("missing_skills"))) {
                var gap = new LinkedHashMap<>(object(raw));
                String name = SkillNames.normalize(text(gap.get("skill_name")));
                gap.put(
                    "nodes",
                    nodes.stream().filter(node -> name.equals(SkillNames.normalize(text(node.get("skill_name")))))
                        .toList()
                );
                missing.add(gap);
            }
            roadmap.put("missing_skills", missing);
        }
        roadmap.put(
            "learning_recommendations",
            snapshot instanceof Map<?, ?>
                ? snapshot
                : references.recommend(
                    text(roadmap.get("target_job_title")),
                    nodes.stream().map(node -> text(node.get("skill_name"))).filter(skill -> !skill.isBlank()).toList()
                )
        );

        return roadmap;
    }

    private Map<String, Object> missingSkillReferences(
        List<Map<String, Object>> missing) {
        List<Map<String, Object>> bySkill = new ArrayList<>();
        Map<String, Map<String, Object>> resources = new LinkedHashMap<>();
        for (var gap : missing) {
            String name = text(gap.get("skill_name"));
            var selected = references.resourcesForSkill(name);
            bySkill.add(fields("skill_name", name, "resources", selected));
            selected.forEach(resource -> resources.putIfAbsent(text(resource.get("url")), resource));
        }
        return fields(
            "learning_scope",
            MissingSkillAnalysis.SCOPE,
            "focus_skills",
            missing.stream().map(gap -> gap.get("skill_name")).toList(),
            "missing_skills",
            missing,
            "skills",
            bySkill,
            "roadmaps",
            List.of(),
            "resources",
            new ArrayList<>(resources.values())
        );
    }

    private List<Map<String, Object>> resourcesForMissingSkill(
        Map<String, Object> snapshot,
        String skill) {
        String name = SkillNames.normalize(skill);
        for (Object raw : array(snapshot.get("skills"))) {
            var row = object(raw);
            if (name.equals(SkillNames.normalize(text(row.get("skill_name"))))) {
                return array(row.get("resources")).stream().map(resource -> object(resource)).toList();
            }
        }
        return List.of();
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
