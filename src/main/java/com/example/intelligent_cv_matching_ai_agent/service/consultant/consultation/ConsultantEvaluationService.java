package com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAtsRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AtsEvaluation;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.AtsScoring;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationEvaluationData;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationEvaluationData.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Candidate report ownership and the consultant's AI usage identity are deliberately separate. */
@Service
public class ConsultantEvaluationService {

    private static final String ATS_VERSION = "consultation-ats-v2";

    private final ConsultationEvaluationRepository repository;

    private final CurrentUser currentUser;

    private final AiService ai;

    private final ResumeService resumes;

    public ConsultantEvaluationService(
        ConsultationEvaluationRepository repository,
        CurrentUser currentUser,
        AiService ai,
        ResumeService resumes) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.ai = ai;
        this.resumes = resumes;
    }

    @Transactional
    public List<Map<String, Object>> candidateResumes(
        UUID consultationId) {
        return repository.listReadyResumes(assignedCandidate(consultationId, false));
    }

    @Transactional
    public Map<String, Object> analyzeResume(
        UUID consultationId,
        UUID resumeId) {
        UUID candidateId = assignedCandidate(consultationId, true);
        Map<String, Object> resume = repository.readyResume(resumeId, candidateId);
        requireAi();

        Map<String, Object> generated = ResumeWritingQuality.normalize(
            ai.generate(
                "CV_ANALYZE",
                fields(
                    "resume",
                    ResumeWritingQuality.source(resume),
                    "resume_text",
                    resumes.canonicalText(resume),
                    "language",
                    languageOf(resume),
                    "evaluation_guidance",
                    "Evaluate CV clarity, completeness, evidence of achievements and improvement opportunities. "
                        + "This is a general CV quality review without a target job. Do not infer job suitability. "
                        + "Base suggestions on reviewed CV facts; never invent candidate qualifications."
                )
            ),
            resume
        );
        double overall = FlowSupport.score(generated.get("overall_score"), 100, "CV overall score");
        Map<String, Object> atsReport = AtsScoring.score(resume);
        Object ats = atsReport.get("score");
        String summary = aiText(generated.get("summary"), "CV summary", 100000, true);
        List<String> strengths = aiStrings(generated, "strengths");
        List<String> issues = aiStrings(generated, "issues");
        List<?> suggestions = aiArray(generated, "suggestions");
        List<Map<String, Object>> checkedSuggestions =
            suggestions.stream().map(ConsultationEvaluationData::suggestion).toList();

        UUID analysisId = repository.insertResumeAnalysis(
            fields(
                "resume_id",
                resumeId,
                "analysis_type",
                "AI_QUALITY",
                "overall_score",
                overall,
                "ats_score",
                ats,
                "summary",
                summary,
                "strengths_json",
                repository.json(strengths),
                "issues_json",
                repository.json(issues),
                "writing_quality_json",
                repository.json(generated.get("writing_quality")),
                "ats_report_json",
                repository.json(atsReport),
                "model_name",
                modelOf(generated)
            )
        );
        for (Map<String, Object> item : checkedSuggestions) {
            item.put("analysis_id", analysisId);
            repository.insertSuggestion(item);
        }
        audit(consultationId, candidateId, resumeId, null, analysisId, "CONSULTATION_CV_ANALYZE");
        return ResumeWritingQuality.expose(repository.resumeAnalysis(analysisId, resumeId));
    }

    @Transactional
    public Map<String, Object> ats(
        UUID consultationId,
        UUID jobId,
        ConsultationAtsRequest request) {
        UUID candidateId = assignedCandidate(consultationId, true);
        UUID resumeId = request.resumeId();
        Map<String, Object> resume = repository.readyResume(resumeId, candidateId);
        Map<String, Object> job = repository.activeJob(jobId);
        List<Map<String, Object>> skills = repository.jobSkills(jobId);
        String canonical = resumes.canonicalText(resume);
        String inputHash = FlowSupport.hash(
            ATS_VERSION + "\n" + AtsScoring.RUBRIC_VERSION + "\n" + canonical + "\n" + resume.get("raw_content") + "\n"
                + resume.get("source") + "\n" + resume.get("mime_type") + "\n" + languageOf(resume) + "\n" + job + "\n"
                + skills + "\n" + ai.provider() + "\n" + ai.model()
        );
        var cached = repository.findAtsAnalysis(candidateId, resumeId, jobId, inputHash);
        if (cached.isPresent()) {
            return atsResponse(cached.get(), true);
        }

        Map<String, Object> evaluation = AtsEvaluation.evaluate(ai, resume, canonical, job, skills, languageOf(resume));
        UUID analysisId = repository.insertAtsAnalysis(
            fields(
                "user_id",
                candidateId,
                "resume_id",
                resumeId,
                "market_job_id",
                jobId,
                "ats_score",
                evaluation.get("ats_score"),
                "strengths_json",
                repository.json(evaluation.get("strengths")),
                "missing_skills_json",
                repository.json(evaluation.get("missing_skills")),
                "improvement_suggestions_json",
                repository.json(
                    fields(
                        "input_hash",
                        inputHash,
                        "items",
                        evaluation.get("improvement_suggestions"),
                        "ats_report",
                        evaluation.get("ats_report")
                    )
                ),
                "model_name",
                evaluation.get("model_name")
            )
        );
        audit(consultationId, candidateId, resumeId, jobId, analysisId, "CONSULTATION_JOB_ATS");
        return atsResponse(repository.atsAnalysis(analysisId, candidateId, resumeId, jobId), false);
    }

    private UUID assignedCandidate(
        UUID consultationId,
        boolean requireOpen) {
        currentUser.requireRole("CAREER_CONSULTANT");
        Map<String, Object> thread = repository.assignedThread(consultationId, currentUser.id());
        if (requireOpen && !"OPEN".equals(thread.get("status"))) {
            throw ApiException.conflict("Consultation is closed");
        }
        return (UUID) thread.get("candidate_user_id");
    }

    private void requireAi() {
        if (!ai.enabled()) {
            throw new ApiException(503, "AI evaluation is disabled. Configure a chat provider before analysis.");
        }
    }

    private String modelOf(
        Map<String, Object> generated) {
        String model = text(generated.get("_model"));
        return model.isBlank() ? ai.model() : model;
    }

    private void audit(
        UUID consultationId,
        UUID candidateId,
        UUID resumeId,
        UUID jobId,
        UUID analysisId,
        String action) {
        repository.insertAudit(
            fields(
                "actor_user_id",
                currentUser.id(),
                "action",
                action,
                "entity_type",
                "consultation_threads",
                "entity_id",
                consultationId,
                "new_value",
                repository.json(
                    fields(
                        "candidate_id",
                        candidateId,
                        "resume_id",
                        resumeId,
                        "job_id",
                        jobId,
                        "analysis_id",
                        analysisId
                    )
                )
            )
        );
    }
}
