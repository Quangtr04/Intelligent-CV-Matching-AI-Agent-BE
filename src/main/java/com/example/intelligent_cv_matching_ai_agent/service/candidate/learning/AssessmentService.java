package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import java.time.OffsetDateTime;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.AssessmentValidator;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
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

        if (jobId == null)
            throw ApiException.badRequest("Select a job description before generating assessment questions");

        Map<String, Object> job = db.getMarketJobPostsById(jobId);
        String jobTitle = text(job.get("job_title")).strip();
        if (jobTitle.isBlank())
            throw ApiException.conflict("The selected job has no title to assess");
        if (target != null && !target.isBlank() && !SkillNames.normalize(target).equals(SkillNames.normalize(jobTitle)))
            throw ApiException.badRequest("The target career must match the selected job description");
        target = jobTitle;

        List<Map<String, Object>> jobSkills =
            db.listMarketJobSkills(jobId).stream().filter(skill -> !text(skill.get("skill_name")).isBlank()).toList();
        if (jobSkills.isEmpty())
            throw ApiException.conflict("The selected job needs extracted skills before generating an assessment");
        List<String> jdSources = new ArrayList<>();
        for (String key : List
            .of("raw_content", "parsed_requirements", "parsed_responsibilities", "parsed_description")) {
            String value = text(job.get(key)).strip();
            if (!value.isBlank())
                jdSources.add(value);
        }
        if (jdSources.isEmpty())
            throw ApiException.conflict("The selected job has no job description to assess");

        var generated = ai.generate(
            "ASSESSMENT_GENERATE",
            fields(
                "target_job_title",
                target,
                "job",
                job,
                "jd_skills",
                jobSkills,
                "jd_text",
                String.join("\n\n", jdSources),
                "profile",
                db.findCandidateProfilesByUserId2(user.id()).orElse(Map.of()),
                "foundation_count",
                5,
                "role_specific_count",
                5
            )
        );
        List<?> questions = array(generated.get("questions"));
        AssessmentValidator.validateQuestions(questions, jobSkills, jdSources);

        db.updateAssessmentSessionsByUserId(user.id(), jobId);

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
            var jobSkill = jobSkills.stream()
                .filter(
                    s -> SkillNames.normalize(text(s.get("skill_name")))
                        .equals(SkillNames.normalize(text(q.get("related_skill_name"))))
                ).findFirst().orElseThrow();
            String skill = text(jobSkill.get("skill_name"));

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
                    "related_skill_id",
                    jobSkill.get("skill_id"),
                    "explanation",
                    q.get("explanation")
                )
            );
        }

        return get(id);
    }

    public static void validateQuestions(
        List<?> questions) {
        AssessmentValidator.validateQuestions(questions);
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
        db.updateRoadmapsByUserId(user.id(), session.get("market_job_id"));

        return get(id);
    }
}
