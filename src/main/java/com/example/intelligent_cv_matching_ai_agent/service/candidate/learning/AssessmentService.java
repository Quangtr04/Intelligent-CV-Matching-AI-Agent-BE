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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class AssessmentService {

    private final AssessmentRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final TransactionTemplate transactions;

    public AssessmentService(
        AssessmentRepository db,
        CurrentUser user,
        AiService ai,
        PlatformTransactionManager transactionManager) {
        this.db = db;
        this.user = user;
        this.ai = ai;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> get(
        UUID id) {
        return getForOwner(id, user.id());
    }

    public Map<String, Object> getForOwner(
        UUID id,
        UUID owner) {
        var session = db.getAssessmentSessionsById(id, owner);
        // Never expose correct answers while an assessment is in progress.
        session.put("questions", db.listQuestionsForSession(id, "completed".equals(session.get("status"))));
        session.put("answers", db.listAssessmentAnswersBySessionId(id));

        return session;
    }

    public Map<String, Object> create(
        String target,
        UUID jobId) {
        user.requireRole("CANDIDATE");
        UUID owner = user.id();
        Prepared prepared = transactions.execute(status -> prepareForOwner(owner, target, jobId));
        Map<String, Object> generated = ai.generate("ASSESSMENT_GENERATE", prepared.input());
        return transactions.execute(status -> persist(prepared, generated));
    }

    /** Prepare a small, server-authorized snapshot; workers never inherit request-thread identity. */
    public Prepared prepareForOwner(
        UUID owner,
        String target,
        UUID jobId) {

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
            .of("parsed_requirements", "parsed_responsibilities", "parsed_description", "raw_content")) {
            String value = text(job.get(key)).strip();
            if (!value.isBlank() && !jdSources.contains(value))
                jdSources.add(value);
        }
        if (jdSources.isEmpty())
            throw ApiException.conflict("The selected job has no job description to assess");

        String excerpt = compactJd(jdSources, jobSkills);
        Map<String, Object> profile = db.findCandidateProfilesByUserId2(owner).orElse(Map.of());
        Map<String, Object> input = fields(
            "target_job_title",
            target,
            "job",
            fields("job_title", target, "role_category", job.get("role_category")),
            "jd_skills",
            jobSkills.stream()
                .map(skill -> fields("skill_name", skill.get("skill_name"), "is_required", skill.get("is_required")))
                .toList(),
            "jd_text",
            excerpt,
            "jd_text_is_excerpt",
            jdSources.stream().mapToInt(String::length).sum() > excerpt.length(),
            "profile",
            fields(
                "target_job_title",
                profile.get("target_job_title"),
                "years_experience",
                profile.get("years_experience")
            ),
            "foundation_count",
            5,
            "role_specific_count",
            5
        );
        return new Prepared(owner, jobId, target, job, jobSkills, jdSources, input);
    }

    private String compactJd(
        List<String> sources,
        List<Map<String, Object>> skills) {
        List<String> excerpts = new ArrayList<>();
        // Keep material from every JD section, then prioritize literal contexts around required skills.
        for (String source : sources) {
            excerpts.add(source.substring(0, Math.min(source.length(), 1800)));
        }
        for (Map<String, Object> skill : skills) {
            String name = text(skill.get("skill_name")).toLowerCase(Locale.ROOT);
            for (String source : sources) {
                int at = source.toLowerCase(Locale.ROOT).indexOf(name);
                if (at >= 0) {
                    excerpts.add(source.substring(Math.max(0, at - 150), Math.min(source.length(), at + 450)));
                    break;
                }
            }
        }
        StringBuilder result = new StringBuilder();
        for (String excerpt : excerpts) {
            if (result.indexOf(excerpt) >= 0 || result.length() + excerpt.length() + 2 > 12000) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(excerpt);
        }
        return result.toString();
    }

    public Map<String, Object> generate(
        Prepared prepared) {
        return ai.generateForUser(prepared.owner(), "ASSESSMENT_GENERATE", prepared.input());
    }

    /** Caller supplies the short publication transaction; no model call is made here. */
    public Map<String, Object> persist(
        Prepared prepared,
        Map<String, Object> generated) {
        UUID owner = prepared.owner();
        UUID jobId = prepared.jobId();
        List<Map<String, Object>> jobSkills = prepared.skills();
        List<?> questions = array(generated.get("questions"));
        AssessmentValidator.validateQuestions(questions, jobSkills, prepared.sources());

        db.getMarketJobPostsById(jobId);
        db.updateAssessmentSessionsByUserId(owner, jobId);

        int foundation = (int) questions.stream().filter(q -> number(object(q).get("part"), 0) == 1).count();

        UUID id = db.insertAssessmentSessions(
            fields(
                "user_id",
                owner,
                "target_job_title",
                prepared.target(),
                "market_job_id",
                jobId,
                "job_role_category_snapshot",
                prepared.job().get("role_category"),
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

        return getForOwner(id, owner);
    }

    public record Prepared(
        UUID owner,
        UUID jobId,
        String target,
        Map<String, Object> job,
        List<Map<String, Object>> skills,
        List<String> sources,
        Map<String, Object> input
    ) {
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
