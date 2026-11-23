package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AssessmentQuestionCount;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.AssessmentValidator;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.LearningInputContext;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContent;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobPostingParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class AssessmentService {

    private static final int SECONDS_PER_QUESTION = 90;

    private final AssessmentRepository db;

    private final CurrentUser user;

    private final AiService ai;

    private final TransactionTemplate transactions;

    private final JsonMapper mapper = JsonMapper.builder().build();

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
        Instant deadline = deadline(session);
        if (deadline != null) {
            session.put("time_limit_seconds", timeLimitSeconds(session));
            session.put("expires_at", deadline.toString());
        }
        // Never expose correct answers while an assessment is in progress.
        session.put("questions", db.listQuestionsForSession(id, "completed".equals(session.get("status"))));
        session.put("answers", db.listAssessmentAnswersBySessionId(id));
        var snapshot = object(session.remove("context_snapshot"));
        if (!snapshot.isEmpty()) {
            session.put(
                "input_context",
                fields(
                    "jd_source",
                    snapshot.get("jd_source"),
                    "job_id",
                    snapshot.get("job_id"),
                    "resume_id",
                    snapshot.get("resume_id"),
                    "target_job_title",
                    snapshot.get("target_job_title")
                )
            );
        }

        return session;
    }

    public Map<String, Object> create(
        String target,
        UUID jobId) {
        return create(new AssessmentRequest(target, jobId));
    }

    public Map<String, Object> create(
        AssessmentRequest request) {
        user.requireRole("CANDIDATE");
        UUID owner = user.id();
        Prepared prepared = transactions.execute(status -> prepareForOwner(owner, request));
        Map<String, Object> generated = ai.generate("ASSESSMENT_GENERATE", prepared.input());
        return transactions.execute(status -> persist(prepared, generated));
    }

    /** Prepare a small, server-authorized snapshot; workers never inherit request-thread identity. */
    public Prepared prepareForOwner(
        UUID owner,
        String target,
        UUID jobId) {
        return prepareForOwner(owner, new AssessmentRequest(target, jobId));
    }

    public Prepared prepareForOwner(
        UUID owner,
        AssessmentRequest request) {
        int count = ai.assessmentQuestionCount();
        return prepareForOwner(
            owner,
            request,
            count >= AssessmentQuestionCount.MINIMUM ? count : AssessmentQuestionCount.DEFAULT
        );
    }

    private Prepared prepareForOwner(
        UUID owner,
        AssessmentRequest request,
        int questionCount) {
        if (!request.isContextValid() || (request.targetJobTitle() != null && request.targetJobTitle().length() > 255)
            || (request.jdText() != null
                && (request.jdText().strip().length() < 30 || request.jdText().length() > 50000))) {
            throw ApiException.badRequest("Select one stored JD or paste 30-50000 characters");
        }
        UUID jobId = request.jobId();
        String target = request.targetJobTitle();
        Map<String, Object> job = jobId == null
            ? fields(
                "job_title",
                target == null || target.isBlank() ? "Pasted JD" : target.strip(),
                "raw_content",
                request.jdText().strip()
            )
            : db.getMarketJobPostsById(jobId);
        String jobTitle = text(job.get("job_title")).strip();
        if (jobTitle.isBlank())
            throw ApiException.conflict("The selected job has no title to assess");
        if (target != null && !target.isBlank() && !SkillNames.normalize(target).equals(SkillNames.normalize(jobTitle)))
            throw ApiException.badRequest("The target career must match the selected job description");
        target = jobTitle;

        List<Map<String, Object>> jobSkills = jobId == null
            ? pastedSkills(request.jdText())
            : db.listMarketJobSkills(jobId).stream().filter(skill -> !text(skill.get("skill_name")).isBlank()).toList();
        if (jobSkills.isEmpty())
            throw ApiException.conflict(
                jobId == null
                    ? "No supported skills were recognized in the pasted JD; include explicit technical skill names"
                    : "The selected job needs extracted skills before generating an assessment"
            );
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
        var extraction = JobRequirementExtractor.forJob(job);
        Set<String> allowedSkills = new HashSet<>();
        jobSkills.forEach(skill -> allowedSkills.add(SkillNames.normalize(text(skill.get("skill_name")))));
        var jdRequirements = extraction.requirements().stream().filter(row -> !row.skills().isEmpty())
            .map(
                row -> fields(
                    "skills",
                    row.skills().stream().map(skill -> skill.name())
                        .filter(name -> allowedSkills.contains(SkillNames.normalize(name))).toList(),
                    "jd_evidence",
                    row.text(),
                    "importance",
                    row.importance(),
                    "relation",
                    row.relation(),
                    "status",
                    row.status()
                )
            ).filter(row -> !((List<?>) row.get("skills")).isEmpty()).toList();
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
        Map<String, Object> resumeSnapshot = Map.of();
        input.put("jd_requirements", jdRequirements);
        input.put(
            "jd_skill_proficiency",
            com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillRubric.currentAnalysis(job)
        );
        input.put("requirement_extraction_version", extraction.version());
        input.put("skill_catalog_version", extraction.catalogVersion());
        if (request.resumeId() != null) {
            var resume = db.getResumeForOwner(request.resumeId(), owner);
            if (!"READY".equals(resume.get("status"))) {
                throw ApiException.conflict("Confirm CV data before generating an assessment");
            }
            String canonical = ResumeContent.canonicalText(mapper, resume);
            if (object(resume.get("structured_data")).isEmpty() && text(resume.get("raw_content")).isBlank()) {
                throw ApiException.conflict("The confirmed CV has no content to assess");
            }
            var declared = db.listResumeSkills(request.resumeId());
            resumeSnapshot = fields(
                "id",
                request.resumeId().toString(),
                "title",
                resume.get("title"),
                "language",
                resume.get("language"),
                "content_hash",
                resume.get("content_hash"),
                "canonical_text",
                canonical,
                "declared_skills",
                declared
            );
            input.put(
                "resume",
                fields(
                    "title",
                    resume.get("title"),
                    "language",
                    resume.get("language"),
                    "text",
                    canonical.substring(0, Math.min(canonical.length(), 16000)),
                    "text_is_excerpt",
                    canonical.length() > 16000,
                    "declared_skills",
                    declared
                )
            );
        }
        Map<String, Object> snapshot = fields(
            "version",
            "cv-jd-learning-proficiency-v3",
            "jd_requirements",
            jdRequirements,
            "requirement_extraction_version",
            extraction.version(),
            "skill_catalog_version",
            extraction.catalogVersion(),
            "owner_id",
            owner.toString(),
            "job_id",
            jobId == null ? null : jobId.toString(),
            "resume_id",
            request.resumeId() == null ? null : request.resumeId().toString(),
            "target_job_title",
            target,
            "jd_source",
            jobId == null ? "PASTED" : "CATALOG",
            "jd_identity",
            jobId == null ? LearningInputContext.hash(jdSources) : jobId.toString(),
            "jd_sources",
            jdSources,
            "jd_skills",
            jobSkills,
            "job",
            jobSnapshot(job),
            "resume",
            resumeSnapshot,
            "profile",
            input.get("profile")
        );
        input.put(AssessmentQuestionCount.INPUT_KEY, questionCount);
        if (questionCount != AssessmentQuestionCount.DEFAULT)
            snapshot.put(AssessmentQuestionCount.INPUT_KEY, questionCount);
        return new Prepared(
            owner,
            jobId,
            target,
            job,
            jobSkills,
            jdSources,
            input,
            request.resumeId(),
            LearningInputContext.hash(snapshot),
            snapshot
        );
    }

    public Prepared restoreForOwner(
        UUID owner,
        Map<String, Object> snapshot,
        String expectedHash) {
        if (!owner.toString().equals(text(snapshot.get("owner_id"))) || expectedHash == null
            || !expectedHash.equals(LearningInputContext.hash(snapshot))) {
            throw ApiException
                .conflict("The learning input snapshot is unavailable or invalid; start a new assessment");
        }
        UUID job = LearningInputContext.optionalUuid(snapshot.get("job_id"));
        UUID resume = LearningInputContext.optionalUuid(snapshot.get("resume_id"));
        String pasted = job == null ? text(object(snapshot.get("job")).get("raw_content")) : null;
        Prepared current = prepareForOwner(
            owner,
            new AssessmentRequest(text(snapshot.get("target_job_title")), job, resume, pasted),
            AssessmentQuestionCount.from(snapshot)
        );
        if (!expectedHash.equals(current.inputHash())) {
            throw ApiException.conflict("CV, JD or assessment inputs changed; generate a new assessment");
        }
        return current;
    }

    private Map<String, Object> jobSnapshot(
        Map<String, Object> job) {
        return fields(
            "job_title",
            job.get("job_title"),
            "role_category",
            job.get("role_category"),
            "parsed_requirements",
            job.get("parsed_requirements"),
            "parsed_responsibilities",
            job.get("parsed_responsibilities"),
            "parsed_description",
            job.get("parsed_description"),
            "raw_content",
            job.get("raw_content")
        );
    }

    private List<Map<String, Object>> pastedSkills(
        String description) {
        String html = "<p>" + HtmlUtils.htmlEscape(description).replace("\n", "</p><p>") + "</p>";
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (var skill : JobPostingParser.extractSkills("", html, "")) {
            found.put(
                SkillNames.normalize(skill.name()),
                fields("skill_name", skill.name(), "is_required", skill.required())
            );
        }
        for (var skill : db.listActiveSkills()) {
            String name = text(skill.get("name"));
            if (!name.isBlank() && Pattern.compile(
                "(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
            ).matcher(description).find()) {
                found.putIfAbsent(SkillNames.normalize(name), fields("skill_name", name, "is_required", true));
                found.get(SkillNames.normalize(name)).put("skill_id", skill.get("id"));
            }
        }
        if (found.size() > 50) {
            throw ApiException.badRequest("The pasted JD contains more than 50 recognized skills");
        }
        return found.values().stream().sorted(Comparator.comparing(row -> text(row.get("skill_name")))).toList();
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
        AssessmentValidator.validateQuestions(
            questions,
            jobSkills,
            prepared.sources(),
            AssessmentQuestionCount.from(prepared.input())
        );
        if (prepared.resumeId() != null) {
            AssessmentValidator.validateCandidateEvidence(
                questions,
                text(object(prepared.snapshot().get("resume")).get("canonical_text"))
            );
        }
        restoreForOwner(owner, prepared.snapshot(), prepared.inputHash());
        if (jobId == null) {
            db.retirePastedContext(owner, prepared.resumeId(), text(prepared.snapshot().get("jd_identity")));
        } else {
            db.updateAssessmentSessionsByUserId(owner, jobId);
        }

        int foundation = (int) questions.stream().filter(q -> number(object(q).get("part"), 0) == 1).count();

        UUID id = db.insertAssessmentSessions(
            fields(
                "user_id",
                owner,
                "target_job_title",
                prepared.target(),
                "market_job_id",
                jobId,
                "resume_id",
                prepared.resumeId(),
                "input_hash",
                prepared.inputHash(),
                "context_snapshot",
                db.json(prepared.snapshot()),
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
                    q.get("explanation"),
                    "cv_evidence",
                    q.get("cv_evidence")
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
        Map<String, Object> input,
        UUID resumeId,
        String inputHash,
        Map<String, Object> snapshot
    ) {
    }

    public static void validateQuestions(
        List<?> questions) {
        AssessmentValidator.validateQuestions(questions);
    }

    private static long timeLimitSeconds(
        Map<String, Object> session) {
        return SECONDS_PER_QUESTION
            * ((long) number(session.get("part1_count"), 0) + (long) number(session.get("part2_count"), 0));
    }

    private static Instant deadline(
        Map<String, Object> session) {
        Instant started = switch (session.get("started_at")) {
            case OffsetDateTime timestamp -> timestamp.toInstant();
            case java.util.Date timestamp -> timestamp.toInstant();
            case Instant timestamp -> timestamp;
            case null, default -> null;
        };
        long seconds = timeLimitSeconds(session);
        return started == null || seconds <= 0 ? null : started.plusSeconds(seconds);
    }

    private static boolean expired(
        Map<String, Object> session) {
        Instant deadline = deadline(session);
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    @Transactional
    public Map<String, Object> answer(
        UUID sessionId,
        UUID questionId,
        String selected) {
        var session = db.getAssessmentSessionsByIdForUpdate(sessionId, user.id());
        if (!"in_progress".equals(session.get("status")))
            throw ApiException.conflict("Assessment already submitted");
        if (expired(session))
            throw ApiException.conflict("Assessment time has expired");
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
        if (missing > 0 && !expired(session))
            throw ApiException.badRequest("Answer all questions before submitting");

        db.updateAssessmentAnswersByQuestionId(id);

        var scores = db.listAssessmentQuestionsByIsCorrect(id);

        db.updateAssessmentSessionsById(db.json(scores), id);
        db.updateRoadmapsByUserId(user.id(), session.get("market_job_id"));

        return get(id);
    }
}
