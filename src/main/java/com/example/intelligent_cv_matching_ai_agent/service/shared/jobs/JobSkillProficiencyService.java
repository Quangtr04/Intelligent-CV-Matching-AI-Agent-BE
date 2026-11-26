package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobSkillProficiencyRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSkillProficiency;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillRubric;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class JobSkillProficiencyService {

    private static final Logger LOG = LoggerFactory.getLogger(JobSkillProficiencyService.class);

    private static final String FEATURE = "JD_SKILL_PROFICIENCY";

    private static final int SKILLS_PER_REQUEST = 20;

    private final JobSkillProficiencyRepository db;

    private final AiService ai;

    private final boolean enabled;

    private final UUID fallbackActor;

    private final long requestDelayMs;

    public JobSkillProficiencyService(
        JobSkillProficiencyRepository db,
        AiService ai,
        @Value("${app.jobs.skill-proficiency.enabled:true}")
        boolean enabled,
        @Value("${app.jobs.skill-proficiency.actor-id:${app.jobs.collection.actor-id:}}")
        String fallbackActor,
        @Value("${app.jobs.skill-proficiency.fixed-delay-ms:20000}")
        long requestDelayMs) {
        this.db = db;
        this.ai = ai;
        this.enabled = enabled;
        this.fallbackActor = fallbackActor.isBlank() ? null : UUID.fromString(fallbackActor);
        this.requestDelayMs = requestDelayMs;
    }

    @Scheduled(fixedDelayString = "${app.jobs.skill-proficiency.fixed-delay-ms:20000}", initialDelay = 20000)
    public void processNext() {
        if (!enabled || !ai.enabled())
            return;
        String rubricVersion = SkillRubric.get().rubricVersion();
        String catalogVersion = SkillCatalog.get().version();
        String version = rubricVersion + ":" + catalogVersion + ":" + JobRequirementExtractor.VERSION;
        UUID lease = UUID.randomUUID();
        db.claim(version, lease, fallbackActor).ifPresent(job -> process(job, lease, version));
    }

    private void process(
        Map<String, Object> job,
        UUID lease,
        String version) {
        UUID jobId = uuid(job.get("id"));
        try {
            db.complete(
                jobId,
                lease,
                version,
                fields(
                    "job_id",
                    jobId,
                    "source_hash",
                    job.get("raw_content_hash"),
                    "rubric_version",
                    SkillRubric.get().rubricVersion(),
                    "catalog_version",
                    SkillCatalog.get().version(),
                    "results",
                    analyze(job, uuid(job.get("actor")), requestDelayMs)
                )
            );
        } catch (RuntimeException failure) {
            String error = failure instanceof ApiException api ? "HTTP_" + api.status() : "INTERNAL";
            if ("AI returned invalid skill proficiency evidence".equals(failure.getMessage()))
                error = "INVALID_EVIDENCE";
            LOG.warn("JD proficiency analysis failed for job {} ({})", jobId, error);
            db.fail(jobId, lease, error);
        }
    }

    public Map<String, Object> preview(
        String jd,
        UUID actor) {
        return fields(
            "rubric_version",
            SkillRubric.get().rubricVersion(),
            "results",
            analyze(fields("raw_content", jd), actor, 0)
        );
    }

    private List<Map<String, Object>> analyze(
        Map<String, Object> job,
        UUID actor,
        long batchDelayMs) {
        var candidates = new ArrayList<>(JobSkillProficiency.candidates(job).values());
        var results = new ArrayList<Map<String, Object>>();
        for (int start = 0; start < candidates.size(); start += SKILLS_PER_REQUEST) {
            if (start > 0 && batchDelayMs > 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(batchDelayMs);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(503, "Job skill analysis interrupted");
                }
            }
            var batch = candidates.subList(start, Math.min(candidates.size(), start + SKILLS_PER_REQUEST));
            var response = ai.generateForUser(actor, FEATURE, JobSkillProficiency.input(job, batch));
            try {
                results.addAll(JobSkillProficiency.validate(batch, response));
            } catch (IllegalArgumentException failure) {
                LOG.warn(
                    "JD proficiency response validation failed for job {}: {}",
                    job.get("id"),
                    failure.getMessage()
                );
                throw new ApiException(502, "AI returned invalid skill proficiency evidence");
            }
        }
        return results;
    }
}
