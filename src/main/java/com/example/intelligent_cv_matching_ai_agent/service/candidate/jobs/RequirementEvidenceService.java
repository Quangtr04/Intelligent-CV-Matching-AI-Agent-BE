package com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.RequirementEvidenceRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs.RequirementEvidence.Chunk;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor;
import java.util.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class RequirementEvidenceService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int BATCH_SIZE = 6;

    private static final String BUDGET_EXHAUSTED = "Interactive evidence budget exhausted";

    @Value("${app.matching.evidence.max-embedding-calls:64}")
    private int maxEmbeddingCalls = 64;

    @Value("${app.matching.evidence.max-verification-calls:4}")
    private int maxVerificationCalls = 4;

    @Value("${app.matching.evidence.request-budget:PT45S}")
    private Duration requestBudget = Duration.ofSeconds(45);

    private final RequirementEvidenceRepository db;

    private final AiService ai;

    private final CurrentUser user;

    public RequirementEvidenceService(
        RequirementEvidenceRepository db,
        AiService ai,
        CurrentUser user) {
        this.db = db;
        this.ai = ai;
        this.user = user;
    }

    /** Per-request state avoids embedding a CV again for each job. No state is shared between candidates. */
    public static final class Candidate {

        private final UUID owner;

        private final List<Chunk> chunks;

        private final Map<String, float[]> vectors = new HashMap<>();

        private final Map<String, Map<String, Object>> verdicts = new HashMap<>();

        private Integer providerFailureStatus;

        private final long deadline;

        private int embeddingCalls;

        private int verificationCalls;

        private Candidate(
            UUID owner,
            List<Chunk> chunks,
            long deadline) {
            this.owner = owner;
            this.chunks = chunks;
            this.deadline = deadline;
        }
    }

    public boolean enabled() {
        return ai.enabled() && ai.embeddingsEnabled();
    }

    public Candidate candidate(
        List<Map<String, Object>> resumes,
        Map<String, Object> profile,
        List<Map<String, Object>> profileSkills) {
        user.requireRole("CANDIDATE");
        for (var resume : resumes) {
            if (!"READY".equals(resume.get("status")))
                throw ApiException.conflict("Confirm CV data before matching");
            if (resume.get("user_id") != null && !user.id().toString().equals(text(resume.get("user_id"))))
                throw ApiException.forbidden("CV belongs to another candidate");
        }
        List<Chunk> chunks = resumes.isEmpty()
            ? RequirementEvidence.profileChunks(profile, profileSkills)
            : RequirementEvidence.resumeChunks(resumes);
        return new Candidate(user.id(), chunks, System.nanoTime() + Math.max(0, requestBudget.toNanos()));
    }

    /** Scheduling priority only; the final score still comes from the same requirement evidence ledger. */
    public double confirmedDeclarationCoverage(
        Candidate candidate,
        Map<String, Object> job) {
        var required =
            RequirementEvidence.requirements(job).stream().filter(row -> "MANDATORY".equals(row.importance())).toList();
        if (required.isEmpty())
            return 0;
        return (double) required.stream()
            .filter(row -> RequirementEvidence.declaredSkillVerdict(row, candidate.chunks).isPresent()).count()
            / required.size();
    }

    public Map<String, Object> assess(
        Candidate candidate,
        Map<String, Object> job) {
        if (!candidate.owner.equals(user.id()))
            throw ApiException.forbidden("Evidence belongs to another candidate");
        if (!enabled())
            throw new ApiException(503, "Requirement evidence matching requires the AI and embedding providers");
        var requirements = RequirementEvidence.requirements(job);
        String extraction = JobRequirementExtractor.forJob(job).status();
        String evaluationDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString();
        String evaluationPolicy = text(ai.cacheFingerprint(RequirementEvidence.FEATURE)) + "\n" + evaluationDate;
        String fingerprint = FlowSupport.hash(
            JSON.writeValueAsString(
                fields(
                    "version",
                    RequirementEvidence.VERSION,
                    "candidate",
                    candidate.chunks,
                    "job_id",
                    job.get("id"),
                    "job_title",
                    job.get("job_title"),
                    "requirements",
                    requirements,
                    "extraction",
                    extraction,
                    "provider",
                    ai.provider(),
                    "embedding_model",
                    ai.embeddingModel(),
                    "generation",
                    evaluationPolicy
                )
            )
        );
        var cached = db.report(candidate.owner, fingerprint);
        if (cached.isPresent())
            return cached.get();

        Map<String, Map<String, Object>> verdicts = new LinkedHashMap<>();
        Map<String, String> verdictHashes = new HashMap<>();
        List<Map<String, Object>> pending = new ArrayList<>();
        List<float[]> cvVectors = List.of();
        for (var requirement : requirements) {
            var direct = RequirementEvidence.declaredSkillVerdict(requirement, candidate.chunks);
            if (direct.isPresent()) {
                verdicts.put(requirement.id(), direct.get());
                continue;
            }
            if (candidate.chunks.isEmpty()) {
                verdicts.put(
                    requirement.id(),
                    fields(
                        "status",
                        "MISSING_EVIDENCE",
                        "evidence",
                        List.of(),
                        "reason",
                        "CV/hồ sơ chưa có thông tin để đối chiếu yêu cầu này."
                    )
                );
                continue;
            }
            String verdictHash = FlowSupport.hash(
                "verdict\n" + RequirementEvidence.VERSION + "\n"
                    + JSON.writeValueAsString(
                        fields(
                            "chunks",
                            candidate.chunks,
                            "role",
                            job.get("job_title"),
                            "text",
                            requirement.text(),
                            "importance",
                            requirement.importance(),
                            "relation",
                            requirement.relation(),
                            "skills",
                            requirement.skills(),
                            "provider",
                            ai.provider(),
                            "embedding",
                            ai.embeddingModel(),
                            "generation",
                            evaluationPolicy
                        )
                    )
            );
            var known = candidate.verdicts.get(verdictHash);
            if (known == null)
                known = db.report(candidate.owner, verdictHash).map(row -> object(row.get("verdict"))).orElse(Map.of());
            if (!known.isEmpty()) {
                verdicts.put(requirement.id(), known);
                candidate.verdicts.put(verdictHash, known);
                continue;
            }
            if (candidate.providerFailureStatus != null) {
                verdicts.put(requirement.id(), deferred(candidate.providerFailureStatus));
                continue;
            }
            verdictHashes.put(requirement.id(), verdictHash);
            try {
                if (cvVectors.isEmpty())
                    cvVectors = candidate.chunks.stream()
                        .map(chunk -> vector(candidate, "CV " + chunk.source().split("/")[1] + "\n" + chunk.text()))
                        .toList();
                String query = "Job role: " + text(job.get("job_title")) + "\n" + requirement.importance()
                    + " requirement: " + requirement.text();
                pending
                    .add(
                        fields(
                            "requirement_id",
                            requirement.id(),
                            "text",
                            requirement.text(),
                            "importance",
                            requirement.importance(),
                            "relation",
                            requirement.relation(),
                            "skills",
                            requirement.skills(),
                            "candidates",
                            RequirementEvidence
                                .retrieve(requirement, vector(candidate, query), candidate.chunks, cvVectors)
                        )
                    );
            } catch (ApiException failure) {
                deferProvider(candidate, failure);
                verdicts.put(requirement.id(), deferred(candidate.providerFailureStatus));
            }
        }
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            var batch = pending.subList(start, Math.min(start + BATCH_SIZE, pending.size()));
            if (candidate.providerFailureStatus != null) {
                batch.forEach(
                    row -> verdicts.put(text(row.get("requirement_id")), deferred(candidate.providerFailureStatus))
                );
                continue;
            }
            Map<String, Map<String, Object>> verified;
            try {
                ensureBudget(candidate, false);
                candidate.verificationCalls++;
                var generated = ai.generate(
                    RequirementEvidence.FEATURE,
                    fields(
                        "job_title",
                        job.get("job_title"),
                        "requirements",
                        batch,
                        "language",
                        "vi",
                        "as_of_date",
                        evaluationDate
                    )
                );
                verified = RequirementEvidence.validate(batch, generated);
            } catch (ApiException failure) {
                deferProvider(candidate, failure);
                batch.forEach(
                    row -> verdicts.put(text(row.get("requirement_id")), deferred(candidate.providerFailureStatus))
                );
                continue;
            }
            verdicts.putAll(verified);
            for (var entry : verified.entrySet()) {
                String hash = verdictHashes.get(entry.getKey());
                candidate.verdicts.put(hash, entry.getValue());
                db.saveReport(candidate.owner, hash, Map.of("verdict", entry.getValue()));
            }
        }
        var report = RequirementEvidence.report(requirements, verdicts, extraction);
        report.put("input_hash", fingerprint);
        boolean deferred =
            verdicts.values().stream().anyMatch(row -> "DEFERRED".equals(row.get("verification_status")));
        report.put("verification_status", deferred ? "DEFERRED" : "VERIFIED");
        if (deferred) {
            report.put("coverage_complete", false);
            report.put("score_kind", "REQUIREMENT_EVIDENCE_LOWER_BOUND");
            report.put("verification_deferred_reason", candidate.providerFailureStatus == 0 ? "BUDGET" : "PROVIDER");
            if (candidate.providerFailureStatus != 0)
                report.put("provider_status", candidate.providerFailureStatus);
            report.put(
                "reason",
                (candidate.providerFailureStatus == 0
                    ? "Đã có kết quả đối chiếu một phần. "
                    : "AI đang tạm thời gián đoạn. ")
                    + "Kết quả chỉ ghi nhận phần đã đối chiếu được; "
                    + ("các yêu cầu chưa xác minh không được coi là thiếu kỹ năng. Bạn có thể "
                        + "cập nhật đề xuất sau.")
            );
            boolean hasVerifiedRequired = array(report.get("requirements")).stream().map(value -> object(value))
                .anyMatch(row -> "MANDATORY".equals(row.get("importance")) && !"UNVERIFIED".equals(row.get("status")));
            if (!hasVerifiedRequired) {
                report.put("requirement_coverage_available", false);
                report.put("requirement_coverage", null);
            }
        } else {
            db.saveReport(candidate.owner, fingerprint, report);
        }
        return report;
    }

    private static void deferProvider(
        Candidate candidate,
        ApiException failure) {
        if (!Set.of(502, 503, 504).contains(failure.status()))
            throw failure;
        candidate.providerFailureStatus = BUDGET_EXHAUSTED.equals(failure.getMessage()) ? 0 : failure.status();
    }

    private static Map<String, Object> deferred(
        int status) {
        return fields(
            "status",
            "UNVERIFIED",
            "verification_status",
            "DEFERRED",
            "reason",
            status == 0
                ? ("Yêu cầu này chưa được AI xác minh trong lượt tải hiện tại; chưa kết " + "luận là thiếu kỹ năng.")
                : ("Chưa xác minh do dịch vụ AI tạm thời gián đoạn; chưa kết luận ứng " + "viên thiếu yêu cầu này."),
            "evidence",
            List.of()
        );
    }

    private float[] vector(
        Candidate candidate,
        String content) {
        String hash = FlowSupport.hash(RequirementEvidence.VERSION + "\n" + content);
        return candidate.vectors.computeIfAbsent(
            hash,
            ignored -> db.vector(candidate.owner, ai.provider(), ai.embeddingModel(), hash).orElseGet(() -> {
                ensureBudget(candidate, true);
                candidate.embeddingCalls++;
                float[] vector = ai.embed(content);
                if (vector == null || vector.length != 1024)
                    throw new ApiException(502, "AI returned incompatible evidence embeddings");
                RequirementEvidence.cosine(vector, vector);
                db.saveVector(candidate.owner, ai.provider(), ai.embeddingModel(), hash, vector);
                return vector;
            })
        );
    }

    private void ensureBudget(
        Candidate candidate,
        boolean embedding) {
        if (System.nanoTime() >= candidate.deadline || (embedding
            ? candidate.embeddingCalls >= Math.max(0, maxEmbeddingCalls)
            : candidate.verificationCalls >= Math.max(0, maxVerificationCalls)))
            throw new ApiException(503, BUDGET_EXHAUSTED);
    }
}
