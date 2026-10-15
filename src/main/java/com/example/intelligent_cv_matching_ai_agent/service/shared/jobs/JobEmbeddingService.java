package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobEmbeddingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
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

/** Claims are committed before provider calls; leases fence publication after retries or concurrent changes. */
@Service
public class JobEmbeddingService {

    private static final Logger LOG = LoggerFactory.getLogger(JobEmbeddingService.class);

    private final JobEmbeddingRepository db;

    private final AiService ai;

    private final TransactionTemplate transactions;

    private final boolean enabled;

    private final int batchSize;

    private final int leaseSeconds;

    private final int retrySeconds;

    public JobEmbeddingService(
        JobEmbeddingRepository db,
        AiService ai,
        PlatformTransactionManager transactionManager,
        @Value("${app.jobs.embedding.enabled:true}")
        boolean enabled,
        @Value("${app.jobs.embedding.batch-size:10}")
        int batchSize,
        @Value("${app.jobs.embedding.lease-seconds:300}")
        int leaseSeconds,
        @Value("${app.jobs.embedding.retry-seconds:30}")
        int retrySeconds) {
        this.db = db;
        this.ai = ai;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 3600));
        this.retrySeconds = Math.max(1, Math.min(retrySeconds, 3600));
    }

    @Transactional
    public UUID enqueue(
        UUID jobId,
        String contentHash,
        UUID requestedBy) {
        if (jobId == null || requestedBy == null || contentHash == null || !contentHash.matches("[0-9a-f]{64}"))
            throw ApiException.badRequest("A job, SHA-256 content hash and requesting administrator are required");
        if (!db.isActiveAdministrator(requestedBy))
            throw ApiException.forbidden("Job embeddings require an active administrator");

        Map<String, Object> job = db.findJobForUpdate(jobId).orElseThrow(() -> ApiException.notFound("Job not found"));
        String status = "QUEUED";
        String error = null;
        if (!usable(job, contentHash)) {
            status = "CANCELLED";
            error = "Job is inactive, expired or its content changed";
        } else if (db.hasCurrentEmbedding(jobId, contentHash, ai.provider(), ai.embeddingModel()))
            status = "COMPLETED";

        return db.enqueue(jobId, contentHash, requestedBy, ai.provider(), ai.embeddingModel(), status, error);
    }

    @Scheduled(
        fixedDelayString = "${app.jobs.embedding.fixed-delay-ms:30000}",
        initialDelayString = "${app.jobs.embedding.initial-delay-ms:10000}"
    )
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processPending() {
        if (!enabled || !ai.embeddingsEnabled())
            return;

        for (int index = 0; index < batchSize; index++) {
            Optional<Map<String, Object>> claimed = transactions
                .execute(status -> db.claimNext(ai.provider(), ai.embeddingModel(), UUID.randomUUID(), leaseSeconds));
            if (claimed == null || claimed.isEmpty())
                return;

            try {
                process(claimed.get());
            } catch (RuntimeException ex) {
                LOG.warn(
                    "Job embedding task {} remains recoverable after {}",
                    claimed.get().get("id"),
                    ex.getClass().getSimpleName()
                );
            }
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> indexNow(
        UUID jobId,
        UUID requestedBy) {
        if (!ai.embeddingsEnabled())
            throw new ApiException(503, "AI embedding provider is disabled");

        UUID taskId = transactions.execute(status -> {
            Map<String, Object> job =
                db.findJobForUpdate(jobId).orElseThrow(() -> ApiException.notFound("Job not found"));
            String hash = Objects.toString(job.get("raw_content_hash"), "");
            if (!usable(job, hash))
                throw ApiException.conflict("Only active, unexpired jobs can be indexed");

            return enqueue(jobId, hash, requestedBy);
        });
        Optional<Map<String, Object>> claimed = transactions.execute(
            status -> db.claimById(taskId, ai.provider(), ai.embeddingModel(), UUID.randomUUID(), leaseSeconds)
        );
        if (claimed != null && claimed.isPresent())
            process(claimed.get());

        return db.task(taskId);
    }

    private void process(
        Map<String, Object> task) {
        if (!"PROCESSING".equals(task.get("status")))
            return;

        UUID taskId = (UUID) task.get("id");
        UUID lease = (UUID) task.get("lease_token");
        Work work = transactions.execute(status -> prepare(task));
        if (work == null)
            return;

        try {
            float[] vector = ai.embedForUser(work.actor(), JobService.embeddingText(work.job()));
            transactions.executeWithoutResult(status -> {
                Map<String, Object> job = db.findJobForUpdate(work.jobId()).orElse(null);
                if (db.findLeaseForUpdate(taskId, lease).isEmpty())
                    return;
                if (job == null || !usable(job, work.hash())) {
                    db.cancel(taskId, lease, "Job changed or expired during embedding");
                    return;
                }

                db.replaceCurrentEmbedding(
                    work.jobId(),
                    ai.provider(),
                    ai.embeddingModel(),
                    work.hash(),
                    Arrays.toString(vector)
                );
                db.complete(taskId, lease);
            });
        } catch (RuntimeException ex) {
            int attempt = ((Number) task.get("attempt_count")).intValue();
            int delay = Math.min(3600, retrySeconds * (1 << Math.min(2, Math.max(0, attempt - 1))));
            transactions.executeWithoutResult(status -> db.retryOrFail(taskId, lease, safeError(ex), delay));
        }
    }

    private Work prepare(
        Map<String, Object> task) {
        UUID taskId = (UUID) task.get("id");
        UUID jobId = (UUID) task.get("entity_id");
        UUID lease = (UUID) task.get("lease_token");
        Map<String, Object> job = db.findJobForUpdate(jobId).orElse(null);
        if (db.findLeaseForUpdate(taskId, lease).isEmpty())
            return null;

        String hash = Objects.toString(task.get("content_hash"), "");
        if (job == null || !usable(job, hash)) {
            db.cancel(taskId, lease, "Job is inactive, expired or its content changed");
            return null;
        }

        UUID actor = (UUID) task.get("requested_by");
        if (actor == null || !db.isActiveAdministrator(actor)) {
            db.cancel(taskId, lease, "The requesting administrator is no longer active");
            return null;
        }
        if (db.hasCurrentEmbedding(jobId, hash, ai.provider(), ai.embeddingModel())) {
            db.complete(taskId, lease);
            return null;
        }

        return new Work(jobId, actor, hash, job);
    }

    private boolean usable(
        Map<String, Object> job,
        String hash) {
        if (!"ACTIVE".equals(job.get("status")) || !Objects.equals(hash, job.get("raw_content_hash")))
            return false;

        Object expiry = job.get("valid_through");
        if (expiry == null)
            return true;
        Instant deadline = expiry instanceof Instant instant
            ? instant
            : expiry instanceof OffsetDateTime date
                ? date.toInstant()
                : expiry instanceof Timestamp timestamp
                    ? timestamp.toInstant()
                    : OffsetDateTime.parse(expiry.toString()).toInstant();

        return deadline.isAfter(Instant.now());
    }

    private String safeError(
        RuntimeException ex) {
        String message = ex instanceof ApiException ? ex.getMessage() : "Embedding provider or publication failed";
        if (message == null)
            return "Embedding failed";

        return message.substring(0, Math.min(message.length(), 1000));
    }

    private record Work(
        UUID jobId,
        UUID actor,
        String hash,
        Map<String, Object> job
    ) {
    }
}
