package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeProfileAlignmentRefreshRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Profile saves enqueue in their transaction. The dedicated worker calls the AI provider only after commit. */
@Service
public class ResumeProfileAlignmentRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeProfileAlignmentRefreshService.class);

    private final ResumeProfileAlignmentRefreshRepository db;

    private final ResumeProfileAlignmentService alignments;

    private final TransactionTemplate transactions;

    private final boolean enabled;

    private final int leaseSeconds;

    public ResumeProfileAlignmentRefreshService(
        ResumeProfileAlignmentRefreshRepository db,
        ResumeProfileAlignmentService alignments,
        PlatformTransactionManager transactionManager,
        @Value("${app.resumes.profile-alignment.refresh.enabled:${CV_PROFILE_ALIGNMENT_REFRESH_ENABLED:true}}")
        boolean enabled,
        @Value("${app.resumes.profile-alignment.refresh.lease-seconds:900}")
        int leaseSeconds) {
        this.db = db;
        this.alignments = alignments;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.leaseSeconds = Math.max(60, Math.min(leaseSeconds, 3600));
    }

    public String profileFingerprint(
        UUID owner) {
        return alignments.profileFingerprint(owner);
    }

    /** Joins the profile save transaction, so a rolled-back edit never leaves an AI request behind. */
    @Transactional
    public int profileUpdated(
        UUID owner,
        String previousFingerprint) {
        String current = alignments.profileFingerprint(owner);
        return current.equals(previousFingerprint) ? 0 : db.enqueueReviewedResumes(owner, current);
    }

    @Scheduled(
        scheduler = "resumeProfileAlignmentScheduler",
        fixedDelayString = "${app.resumes.profile-alignment.refresh.fixed-delay-ms:5000}",
        initialDelayString = "${app.resumes.profile-alignment.refresh.initial-delay-ms:15000}"
    )
    public void processPending() {
        if (enabled) {
            processNext();
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean processNext() {
        Optional<Map<String, Object>> claimed = transactions.execute(status -> {
            db.expireInterrupted();
            return db.claimNext(UUID.randomUUID(), leaseSeconds);
        });
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        Map<String, Object> request = claimed.get();
        UUID resume = (UUID) request.get("resume_id");
        UUID owner = (UUID) request.get("user_id");
        UUID token = (UUID) request.get("lease_token");
        long generation = ((Number) request.get("generation")).longValue();
        try {
            alignments.createForRefresh(resume, owner, token, generation);
        } catch (RuntimeException ex) {
            int http = ex instanceof ApiException api ? api.status() : 500;
            String terminal = http == 404 || http == 409 ? "CANCELLED" : "FAILED";
            String code = switch (http) {
                case 400 -> "INVALID_INPUT";
                case 401, 403 -> "ACCOUNT_OR_ENTITLEMENT";
                case 404 -> "CV_UNAVAILABLE";
                case 409 -> "INPUT_CHANGED";
                case 429 -> "QUOTA_EXHAUSTED";
                case 502 -> "AI_RESULT_INVALID";
                case 503 -> "AI_UNAVAILABLE";
                default -> "INTERNAL_ERROR";
            };
            String message = switch (http) {
                case 429 -> "Not enough AI quota. Retry manually after quota is available.";
                case 503 -> "AI is unavailable. Retry manually when the local model is ready.";
                case 400 -> "CV or profile input cannot be compared. Review the data before retrying.";
                case 401, 403 -> "The account or its AI entitlement does not allow this comparison.";
                case 404, 409 -> "CV or career profile changed or is no longer ready. Review it before retrying.";
                default -> "Automatic comparison could not finish. The previous report is preserved. Retry manually.";
            };
            transactions.executeWithoutResult(
                status -> db.failOrRequeue(resume, owner, token, generation, terminal, code, message)
            );
            LOG.info("Automatic CV/profile comparison {} ended with {}", resume, code);
        }
        return true;
    }
}
