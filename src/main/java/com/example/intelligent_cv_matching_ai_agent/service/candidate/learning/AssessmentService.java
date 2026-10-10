package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class AssessmentService {

    private final AssessmentRepository db;

    private final CurrentUser user;

    private final AiService ai;

    public AssessmentService(
        AssessmentRepository db,
        CurrentUser user,
        AiService ai) {
        this.db = db;
        this.user = user;
        this.ai = ai;
    }

    public Map<String, Object> get(
        UUID id) {
        var session = db.getAssessmentSessionsById(id, user.id());
        // Never expose correct answers while an assessment is in progress.
        session.put("questions", db.listQuestionsForSession(id, "completed".equals(session.get("status"))));
        session.put("answers", db.listAssessmentAnswersBySessionId(id));

        return session;
    }

    @Transactional
    public Map<String, Object> create(
        String target,
        UUID jobId) {
        user.requireRole("CANDIDATE");

        Map<String, Object> job = jobId == null ? Map.of() : db.getMarketJobPostsById(jobId);

        if (target == null || target.isBlank())
            target = text(job.get("job_title"));

        if (target.isBlank())
            target = text(db.findCandidateProfilesByUserId(user.id()).orElse(Map.of()).get("target_job_title"));

        if (target.isBlank() || target.length() > 255)
            throw ApiException.badRequest("Select a target career first");

        var generated = ai.generate(
            "ASSESSMENT_GENERATE",
            fields(
                "target_job_title",
                target,
                "job",
                job,
                "profile",
                db.findCandidateProfilesByUserId2(user.id()).orElse(Map.of()),
                "foundation_count",
                5,
                "role_specific_count",
                5
            )
        );
        List<?> questions = array(generated.get("questions"));
        validateQuestions(questions);

        db.updateAssessmentSessionsByUserId(user.id(), target);

        int foundation = (int) questions.stream().filter(q -> number(object(q).get("part"), 0) == 1).count();

        UUID id = db.insertAssessmentSessions(
            fields(
                "user_id",
                user.id(),
                "target_job_title",
                target,
                "market_job_id",
                jobId,
                "job_role_category_snapshot",
                job.get("role_category"),
                "part1_count",
                foundation,
                "part2_count",
                questions.size() - foundation,
                "started_at",
                OffsetDateTime.now()
            )
        );

        int order = 1;

        for (Object raw : questions) {
            var q = object(raw);
            String skill = text(q.get("related_skill_name"));

            db.insertAssessmentQuestions(
                fields(
                    "session_id",
                    id,
                    "sequence_order",
                    order++,
                    "part",
                    (int) number(q.get("part"), 1),
                    "question_text",
                    q.get("question_text"),
                    "options",
                    db.json(q.get("options")),
                    "correct_option",
                    q.get("correct_option"),
                    "related_skill_name",
                    skill,
                    "explanation",
                    q.get("explanation")
                )
            );
        }

        return get(id);
    }

    public static void validateQuestions(
        List<?> questions) {
        if (questions.size() < 2 || questions.size() > 30)
            throw new ApiException(502, "AI must generate 2..30 assessment questions");

        boolean foundation = false, technical = false;

        for (Object raw : questions) {
            var q = object(raw);
            double part = number(q.get("part"), 0);

            if (part == 1)
                foundation = true;
            else if (part == 2)
                technical = true;
            else
                throw new ApiException(502, "Invalid assessment part");

            if (!(q.get("question_text") instanceof String question) || question.isBlank() || question.length() > 5000
                || q.get("related_skill_name") != null
                    && (!(q.get("related_skill_name") instanceof String skill) || skill.length() > 150)
                || q.get("explanation") != null
                    && (!(q.get("explanation") instanceof String explanation) || explanation.length() > 10000))
                throw new ApiException(502, "Invalid question content");

            Map<String, Object> options = object(q.get("options"));
            if (!options.keySet().equals(Set.of("A", "B", "C", "D"))
                || options.values().stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 2000)
                || !(q.get("correct_option") instanceof String correct) || !options.containsKey(correct))
                throw new ApiException(502, "Invalid answer choices");
        }

        if (!foundation || !technical)
            throw new ApiException(502, "Assessment must contain foundation and role-specific questions");
    }

    @Transactional
    public Map<String, Object> answer(
        UUID sessionId,
        UUID questionId,
        String selected) {
        var session = db.getAssessmentSessionsByIdForUpdate(sessionId, user.id());
        if (!"in_progress".equals(session.get("status")))
            throw ApiException.conflict("Assessment already submitted");
        if (!Set.of("A", "B", "C", "D").contains(selected))
            throw ApiException.badRequest("Answer must be A, B, C or D");

        db.getAssessmentQuestionsById(questionId, sessionId);

        db.updateAssessmentAnswers(sessionId, questionId, selected);

        return Map.of("question_id", questionId, "selected_option", selected);
    }

    @Transactional
    public Map<String, Object> submit(
        UUID id) {
        var session = db.getAssessmentSessionsByIdForUpdate(id, user.id());
        if ("completed".equals(session.get("status")))
            return get(id);
        if (!"in_progress".equals(session.get("status")))
            throw ApiException.conflict("Assessment cannot be submitted");

        long missing = ((Number) db.countAssessmentQuestionsBySessionId(id)).longValue();
        if (missing > 0)
            throw ApiException.badRequest("Answer all questions before submitting");

        db.updateAssessmentAnswersByQuestionId(id);

        var scores = db.listAssessmentQuestionsByIsCorrect(id);

        db.updateAssessmentSessionsById(db.json(scores), id);
        db.updateRoadmapsByUserId(user.id(), session.get("target_job_title"));

        return get(id);
    }
}
