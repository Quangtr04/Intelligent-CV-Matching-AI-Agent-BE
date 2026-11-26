package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentAnswerRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapNodeStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.RoadmapValidator;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.MissingSkillAnalysis;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.LearningInputContext;

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
        return assessments.create(request);
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
        result.put("roadmap_request", fields("assessmentId", session.get("id")));
        return result;
    }

    public Map<String, Object> createRoadmap(
        RoadmapRequest request) {
        return createRoadmap(request, result -> {
        });
    }

    public Map<String, Object> createRoadmap(
        RoadmapRequest request,
        java.util.function.Consumer<Map<String, Object>> onPublished) {
        user.requireRole("CANDIDATE");
        RoadmapInputs inputs = transactions.execute(status -> resolveRoadmapInputs(request));
        var saved = transactions.execute(status -> reusableRoadmap(inputs).map(result -> {
            onPublished.accept(result);
            return result;
        }));
        if (saved.isPresent()) {
            return saved.get();
        }

        ai.requireRoadmapAccess();
        PreparedRoadmap prepared = modelCalls.execute(status -> prepareRoadmap(inputs));
        prepared.recommendations().putAll(missingSkillReferences(prepared.missing()));
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
            : generateLearningOrUseCatalog(prepared);
        return transactions.execute(status -> {
            Map<String, Object> result = publishRoadmap(prepared, generated);
            onPublished.accept(result);
            return result;
        });
    }

    private Map<String, Object> generateLearningOrUseCatalog(
        PreparedRoadmap prepared) {
        try {
            return generateLearning(prepared);
        } catch (AiService.ProviderUnavailable failure) {
            prepared.recommendations().put("generation_source", "CATALOG_FALLBACK");
            var nodes = prepared.missing().stream().map(
                skill -> fields(
                    "skill_name",
                    skill.get("skill_name"),
                    "description",
                    "Xem lại kiến thức cơ bản về " + text(skill.get("skill_name"))
                        + ". Sau đó, chọn một yêu cầu liên quan trong JD và làm một bài thực hành nhỏ để tự kiểm tra.",
                    "estimated_hours",
                    1,
                    "prerequisites",
                    List.of()
                )
            ).toList();
            return fields(
                "title",
                "Lộ trình ôn tập theo JD",
                "summary",
                "Bài ôn tập cơ bản theo các kỹ năng được chọn từ JD.",
                "nodes",
                nodes
            );
        }
    }

    private Map<String, Object> generateLearning(
        PreparedRoadmap prepared) {
        try {
            return generateLearningBatch(prepared.input(), prepared.missing());
        } catch (AiService.OutputTruncated failure) {
            if (prepared.missing().size() <= 12)
                throw failure;
        }

        // Use smaller groups only when the complete response exceeded the model's output limit.
        final int batchSize = 12;
        List<Map<String, Object>> combined = new ArrayList<>();
        Map<String, Object> result = null;
        for (int start = 0; start < prepared.missing().size(); start += batchSize) {
            var batch = prepared.missing().subList(start, Math.min(start + batchSize, prepared.missing().size()));
            var generated = generateLearningBatch(prepared.input(), batch);
            MissingSkillAnalysis.constrainGenerated(generated, batch);
            if (result == null) {
                result = generated;
            }
            array(generated.get("nodes")).forEach(raw -> combined.add(object(raw)));
        }
        result.put("nodes", combined);
        return result;
    }

    private Map<String, Object> generateLearningBatch(
        Map<String, Object> context,
        List<Map<String, Object>> skills) {
        Map<String, Object> input = new LinkedHashMap<>(context);
        input.put("allowed_missing_skills", skills);
        var generated = new LinkedHashMap<>(modelCalls.execute(status -> ai.generate("ROADMAP_GENERATE", input)));
        if (generated.get("nodes") instanceof List<?> nodes) {
            generated.put("nodes", nodes.stream().map(raw -> {
                var node = new LinkedHashMap<>(object(raw));
                // Temporarily publish independent learning items, regardless of the model's references.
                node.put("prerequisites", List.of());
                return node;
            }).toList());
        }
        return generated;
    }

    private RoadmapInputs resolveRoadmapInputs(
        RoadmapRequest request) {
        if (!request.isContextValid()) {
            throw ApiException.badRequest("Select a JD or a completed CV/JD assessment");
        }
        UUID jobId = request.jobId();
        UUID resumeId = request.resumeId();
        var profile = db.findCandidateProfilesByUserId(user.id()).orElse(Map.of());
        var cv = resumeId == null ? Map.<String, Object>of() : resumes.owned(resumeId);
        if (!cv.isEmpty() && !"READY".equals(cv.get("status")))
            throw ApiException.conflict("Confirm CV data first");

        Map<String, Object> assessment;
        if (request.assessmentId() != null) {
            assessment = db.getAssessmentSessionsById(request.assessmentId(), user.id());
            UUID assessmentJob = LearningInputContext.optionalUuid(assessment.get("market_job_id"));
            if (jobId != null && !jobId.equals(assessmentJob)) {
                throw ApiException.badRequest("Assessment must be completed for the same selected JD");
            }
            jobId = assessmentJob;
            UUID assessmentResume = LearningInputContext.optionalUuid(assessment.get("resume_id"));
            if (resumeId != null && !resumeId.equals(assessmentResume)) {
                throw ApiException.badRequest("Assessment must use the same confirmed CV");
            }
            resumeId = assessmentResume;
        } else if (resumeId != null) {
            assessment = db.findCompletedAssessmentForResume(user.id(), jobId, resumeId)
                .orElseThrow(() -> ApiException.conflict("Complete a CV/JD assessment before creating its roadmap"));
        } else {
            assessment = db.findCompletedAssessmentForJob(user.id(), jobId).orElse(Map.of());
        }

        AssessmentService.Prepared assessedContext = null;
        Map<String, Object> assessmentSnapshot = object(assessment.get("context_snapshot"));
        if (!assessmentSnapshot.isEmpty()) {
            try {
                assessedContext =
                    assessments.restoreForOwner(user.id(), assessmentSnapshot, text(assessment.get("input_hash")));
                if (!Objects.equals(jobId, assessedContext.jobId())
                    || !Objects.equals(resumeId, assessedContext.resumeId())) {
                    throw ApiException.conflict("Assessment context does not match its selected CV and JD");
                }
            } catch (ApiException ex) {
                boolean optionalAssessment = request.assessmentId() == null && resumeId == null && jobId != null;
                if (!optionalAssessment || !Set.of(400, 404, 409).contains(ex.status())) {
                    throw ex;
                }
                // A direct profile/JD roadmap remains available when an old optional quiz is stale.
                assessment = Map.of();
                assessmentSnapshot = Map.of();
                assessedContext = null;
            }
        } else if (resumeId != null || jobId == null) {
            throw ApiException.conflict("Generate and complete a new assessment using this confirmed CV and JD");
        }
        if (resumeId != null && cv.isEmpty()) {
            cv = resumes.owned(resumeId);
            if (!"READY".equals(cv.get("status"))) {
                throw ApiException.conflict("Confirm CV data first");
            }
        }
        var job = assessedContext != null ? assessedContext.job() : db.getMarketJobPostsById(jobId);
        String target = text(job.get("job_title"));
        if (target.isBlank()) {
            throw ApiException.badRequest("Select a target career");
        }

        var profileSkills = db.listCandidateProfileSkillsByUserId(user.id());
        List<Map<String, Object>> requirements =
            assessedContext != null ? assessedContext.skills() : db.listMarketJobSkillsByMarketJobId(jobId);
        var resumeSkills = resumeId == null ? List.<Map<String, Object>>of() : db.listResumeSkillsByResumeId(resumeId);
        var skillCatalog = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get();
        var extractedRequirements =
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job);
        var requirementSnapshot =
            tools.jackson.databind.json.JsonMapper.builder().build().convertValue(extractedRequirements, Map.class);
        String contextKey = jobId != null ? "job:" + jobId : "paste:" + text(assessmentSnapshot.get("jd_identity"));
        Map<String, Object> sourceSnapshot = fields(
            "job_id",
            jobId,
            "resume_id",
            resumeId,
            "context_key",
            contextKey,
            "job",
            fields(
                "job_title",
                target,
                "raw_content",
                job.get("raw_content"),
                "parsed_requirements",
                job.get("parsed_requirements"),
                "parsed_responsibilities",
                job.get("parsed_responsibilities"),
                "parsed_description",
                job.get("parsed_description")
            ),
            "requirements",
            requirements,
            "profile_skills",
            profileSkills,
            "resume_skills",
            resumeSkills,
            "profile",
            fields("years_experience", profile.get("years_experience")),
            "assessment_id",
            assessment.get("id"),
            "assessment_scores",
            assessment.get("skill_scores"),
            "assessment_input_hash",
            assessment.get("input_hash")
        );
        sourceSnapshot.put("catalog_version", skillCatalog.version());
        sourceSnapshot.put("structured_requirements", requirementSnapshot);
        sourceSnapshot.put("roadmap_selection_version", 1);
        return new RoadmapInputs(
            user.id(),
            request,
            job,
            profile,
            profileSkills,
            resumeSkills,
            requirements,
            assessment,
            jobId,
            resumeId,
            contextKey,
            sourceSnapshot
        );
    }

    private PreparedRoadmap prepareRoadmap(
        RoadmapInputs inputs) {
        var job = inputs.job();
        var assessment = inputs.assessment();
        var profile = inputs.profile();
        var profileSkills = inputs.profileSkills();
        var requirements = inputs.requirements();
        var resumeSkills = inputs.resumeSkills();
        UUID jobId = inputs.jobId();
        UUID resumeId = inputs.resumeId();
        String contextKey = inputs.contextKey();
        String target = text(job.get("job_title"));
        var extractedRequirements =
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor.forJob(job);
        var sourceSnapshot = new LinkedHashMap<>(inputs.snapshot());
        var analysis = MissingSkillAnalysis.analyze(job, requirements, profileSkills, resumeSkills, assessment);
        var missing = analysis.learning();
        var skillCatalog = com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get();
        var catalogLearning = skillCatalog.learningContext(missing);
        var candidateSkillNames = new java.util.HashSet<String>();
        profileSkills.forEach(skill -> candidateSkillNames.add(text(skill.get("name"))));
        resumeSkills.forEach(skill -> candidateSkillNames.add(text(skill.get("name"))));
        var unresolved = MissingSkillAnalysis.unverifiedRequirements(job, candidateSkillNames);
        sourceSnapshot.put("catalog_learning_context", catalogLearning);
        String gapVersion = "jd-skills-v3";
        sourceSnapshot.put("skill_gap_version", gapVersion);
        sourceSnapshot.put("skill_states", analysis.skills());
        var recommendationSnapshot = fields();
        recommendationSnapshot.put("catalog_version", skillCatalog.version());
        recommendationSnapshot.put("skill_states", analysis.skills());
        recommendationSnapshot.put("gap_analysis_version", gapVersion);
        recommendationSnapshot.put("unverified_requirements", unresolved);
        recommendationSnapshot.put("catalog_learning_context", catalogLearning);
        recommendationSnapshot.put(
            "input_context",
            fields(
                "jd_source",
                jobId == null ? "PASTED" : "CATALOG",
                "job_id",
                jobId,
                "resume_id",
                resumeId,
                "assessment_id",
                assessment.get("id"),
                "context_key",
                contextKey
            )
        );
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
            "resource_policy",
            "Resource URLs are attached by the server."
        );
        input.put("catalog_version", skillCatalog.version());
        input.put("catalog_learning_context", catalogLearning);
        input.put("unverified_requirements", unresolved);
        var missingKeys = missing.stream().map(gap -> SkillNames.normalize(text(gap.get("skill_name"))))
            .collect(java.util.stream.Collectors.toSet());
        input.put(
            "jd_requirement_evidence",
            extractedRequirements.requirements().stream()
                .filter(
                    row -> row.skills().stream()
                        .anyMatch(skill -> missingKeys.contains(SkillNames.normalize(skill.name())))
                )
                .map(
                    row -> fields(
                        "text",
                        row.text().substring(0, Math.min(row.text().length(), 1000)),
                        "importance",
                        row.importance(),
                        "relation",
                        row.relation()
                    )
                ).toList()
        );
        return new PreparedRoadmap(
            inputs,
            target,
            assessment,
            !assessment.isEmpty() ? "ASSESSMENT" : resumeId != null ? "RESUME" : "JOB",
            missing,
            recommendationSnapshot,
            input,
            jobId,
            resumeId,
            contextKey,
            LearningInputContext.hash(sourceSnapshot),
            sourceSnapshot
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

    private java.util.Optional<Map<String, Object>> reusableRoadmap(
        RoadmapInputs inputs) {
        return db.findReusableRoadmap(inputs.owner(), inputs.contextKey(), inputs.snapshot()).map(this::roadmap);
    }

    private Map<String, Object> publishRoadmap(
        PreparedRoadmap prepared,
        Map<String, Object> generated) {
        RoadmapRequest request = prepared.inputs().request();
        UUID owner = prepared.inputs().owner();
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
        RoadmapInputs latestInputs = resolveRoadmapInputs(request);
        if (!LearningInputContext.hash(prepared.inputs().snapshot())
            .equals(LearningInputContext.hash(latestInputs.snapshot()))) {
            throw ApiException.conflict("Learning inputs changed while generating the roadmap; review and retry");
        }
        var saved = reusableRoadmap(latestInputs);
        if (saved.isPresent()) {
            return saved.get();
        }
        int version = ((Number) (prepared.jobId() == null
            ? db.readGapVersionByContext(owner, prepared.contextKey())
            : db.readGapAnalysesByUserId(owner, prepared.jobId()))).intValue();
        if (prepared.jobId() == null) {
            db.retireGapContext(owner, prepared.contextKey());
        } else {
            db.updateGapAnalysesByUserId(owner, prepared.jobId());
        }
        UUID gapId = db.insertGapAnalyses(
            fields(
                "user_id",
                owner,
                "market_job_id",
                prepared.jobId(),
                "resume_id",
                prepared.resumeId(),
                "context_key",
                prepared.contextKey(),
                "input_hash",
                prepared.inputHash(),
                "context_snapshot",
                db.json(prepared.snapshot()),
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
        boolean needsReview = !array(recommendationSnapshot.get("unverified_requirements")).isEmpty()
            || array(recommendationSnapshot.get("skill_states")).stream()
                .anyMatch(raw -> "DECLARED".equals(object(raw).get("status")));

        UUID roadmapId = db.insertRoadmaps(
            fields(
                "user_id",
                owner,
                "market_job_id",
                prepared.jobId(),
                "gap_analysis_id",
                gapId,
                "target_job_title",
                target,
                "title",
                title,
                "estimated_total_hours",
                hours,
                "status",
                missing.isEmpty() && !needsReview ? "completed" : "ready",
                "progress_percent",
                missing.isEmpty() && !needsReview ? 100 : 0,
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

    private record RoadmapInputs(
        UUID owner,
        RoadmapRequest request,
        Map<String, Object> job,
        Map<String, Object> profile,
        List<Map<String, Object>> profileSkills,
        List<Map<String, Object>> resumeSkills,
        List<Map<String, Object>> requirements,
        Map<String, Object> assessment,
        UUID jobId,
        UUID resumeId,
        String contextKey,
        Map<String, Object> snapshot
    ) {
    }

    private record PreparedRoadmap(
        RoadmapInputs inputs,
        String target,
        Map<String, Object> assessment,
        String inputSource,
        List<Map<String, Object>> missing,
        Map<String, Object> recommendations,
        Map<String, Object> input,
        UUID jobId,
        UUID resumeId,
        String contextKey,
        String inputHash,
        Map<String, Object> snapshot
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

    public List<Map<String, Object>> roadmaps(
        UUID assessmentId) {
        List<Map<String, Object>> roadmaps = assessmentId == null
            ? db.listRoadmapsByUserId(user.id())
            : db.listRoadmapsByAssessment(user.id(), assessmentId);
        roadmaps.forEach(roadmap -> {
            var snapshot = object(roadmap.remove("learning_recommendations_snapshot"));
            if (snapshot.containsKey("input_context")) {
                roadmap.put("input_context", snapshot.get("input_context"));
            }
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
            var resources = new ArrayList<>(db.listRoadmapNodeResourcesByRoadmapNodeId(node.get("id")));
            for (var course : references.approvedCoursesForSkill(text(node.get("skill_name")))) {
                boolean alreadyAttached = resources.stream().anyMatch(
                    resource -> Objects.equals(resource.get("id"), course.get("id"))
                        || Objects.equals(resource.get("url"), course.get("url"))
                );
                if (!alreadyAttached) {
                    resources.add(course);
                }
            }
            node.put("resources", resources);
        }

        roadmap.put("nodes", nodes);
        var gaps = db.listGapAnalysisSkillsByGapAnalysisId(roadmap.get("gap_analysis_id"));
        roadmap.put("skill_gaps", gaps);
        Object snapshot = roadmap.remove("learning_recommendations_snapshot");
        roadmap.put("skill_states", array(object(snapshot).get("skill_states")));
        roadmap.put("unverified_requirements", array(object(snapshot).get("unverified_requirements")));
        roadmap.put("gap_analysis_version", object(snapshot).getOrDefault("gap_analysis_version", "LEGACY"));
        roadmap.put("generation_source", object(snapshot).getOrDefault("generation_source", "STANDARD"));
        if (object(snapshot).containsKey("input_context")) {
            roadmap.put("input_context", object(snapshot).get("input_context"));
        }
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
