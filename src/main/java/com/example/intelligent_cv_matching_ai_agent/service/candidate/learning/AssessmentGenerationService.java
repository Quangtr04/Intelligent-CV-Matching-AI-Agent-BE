package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.AssessmentRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentGenerationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable question-generation worker, independent of a browser request or security thread. */
@Service
public class AssessmentGenerationService {

    private final AssessmentGenerationRepository db;

    private final AssessmentService assessments;

    private final CurrentUser current;

    private final TransactionTemplate transactions;

    private final boolean enabled;

    private final int leaseSeconds;

    public AssessmentGenerationService(
        AssessmentGenerationRepository db,
        AssessmentService assessments,
        CurrentUser current,
        PlatformTransactionManager manager,
        @Value("${app.assessments.generation.enabled:true}")
        boolean enabled,
        @Value("${app.assessments.generation.lease-seconds:1800}")
        int leaseSeconds) {
        this.db = db;
        this.assessments = assessments;
        this.current = current;
        this.transactions = new TransactionTemplate(manager);
        this.enabled = enabled;
        this.leaseSeconds = Math.max(600, Math.min(leaseSeconds, 3600));
    }

    public Map<String, Object> start(
        AssessmentRequest input) {
        current.requireRole("CANDIDATE");
        UUID owner = current.id();
        if (!enabled) {
            throw new ApiException(503, "Question generation is temporarily unavailable.");
        }
        return transactions.execute(status -> {
            db.lockQueue();
            db.expireInterrupted();
            requireActive(owner);
            AssessmentService.Prepared prepared = assessments.prepareForOwner(owner, input);
            Optional<Map<String, Object>> existing = db.active(owner, prepared.inputHash());
            if (existing.isPresent()) {
                return existing.get();
            }
            if (db.activeCount(owner) >= 3 || db.activeCount(null) >= 50) {
                throw new ApiException(429, "Question generation is busy. Wait for an existing request to finish.");
            }
            return db.enqueue(
                owner,
                prepared.jobId(),
                prepared.target(),
                prepared.resumeId(),
                prepared.inputHash(),
                prepared.snapshot()
            );
        });
    }

    public Map<String, Object> status(
        UUID id) {
        current.requireRole("CANDIDATE");
        return db.status(id, current.id());
    }

    public List<Map<String, Object>> latest(
        UUID job) {
        current.requireRole("CANDIDATE");
        return db.latest(current.id(), job);
    }

    @Scheduled(
        scheduler = "assessmentGenerationScheduler",
        fixedDelayString = "${app.assessments.generation.fixed-delay-ms:1000}",
        initialDelayString = "${app.assessments.generation.initial-delay-ms:5000}"
    )
    public void processPending() {
        if (enabled) {
            processNext();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean processNext() {
        Optional<Map<String, Object>> claimed = transactions.execute(status -> {
            db.lockQueue();
            db.expireInterrupted();
            return db.claimNext(UUID.randomUUID(), leaseSeconds);
        });
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        Map<String, Object> request = claimed.get();
        UUID id = (UUID) request.get("id");
        UUID owner = (UUID) request.get("user_id");
        UUID job = (UUID) request.get("job_id");
        UUID token = (UUID) request.get("lease_token");
        try {
            AssessmentService.Prepared prepared = transactions.execute(status -> {
                requireActive(owner);
                Map<String, Object> snapshot = com.example.intelligent_cv_matching_ai_agent.util.shared.Values
                    .object(request.get("context_snapshot"));
                if (snapshot.isEmpty()) {
                    // A request queued before the context migration remains a catalog-only legacy assessment.
                    return assessments.prepareForOwner(owner, (String) request.get("target_job_title"), job);
                }
                return assessments.restoreForOwner(owner, snapshot, (String) request.get("input_hash"));
            });
            // No JDBC transaction/connection is held while the AI provider runs.
            Map<String, Object> generated = assessments.generate(prepared);
            transactions.executeWithoutResult(status -> {
                if (!db.ownsClaim(id, owner, token)) {
                    throw ApiException.conflict("Question generation was interrupted before publication.");
                }
                requireActive(owner);
                Map<String, Object> assessment = assessments.persist(prepared, generated);
                if (db.complete(id, token, (UUID) assessment.get("id")) != 1) {
                    throw ApiException.conflict("Question generation lost its publication lease.");
                }
            });
        } catch (RuntimeException ex) {
            int http = ex instanceof ApiException api ? api.status() : 500;
            String code = switch (http) {
                case 400, 404, 409 -> "INPUT_UNAVAILABLE";
                case 401, 403 -> "ACCOUNT_UNAVAILABLE";
                case 429 -> "QUOTA_EXHAUSTED";
                case 502 -> "AI_RESULT_INVALID";
                case 503 -> "AI_UNAVAILABLE";
                case 504 -> "AI_TIMEOUT";
                default -> "GENERATION_FAILED";
            };
            String message = switch (http) {
                case 429 -> "Not enough AI quota. Retry after quota is available.";
                case 503 -> "The AI provider is unavailable. Check the provider configuration and retry.";
                case 504 -> "The AI provider took too long. Retry manually when the provider is available.";
                case 400, 404, 409 ->
                    "The selected CV, JD or generation inputs changed or became unavailable. Review and retry.";
                case 401, 403 -> "The account no longer allows question generation.";
                default -> "AI could not finish a valid question set. Retry manually; no partial assessment was saved.";
            };
            transactions.executeWithoutResult(status -> db.fail(id, token, code, message));
        }
        return true;
    }

    private void requireActive(
        UUID owner) {
        if (!db.activeCandidate(owner)) {
            throw ApiException.forbidden("An active candidate account is required.");
        }
    }
}
