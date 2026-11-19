package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobEmbeddingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
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
        String inputHash = JobEmbeddingSupport.inputHash(job);
        String status = "QUEUED";
        String error = null;
        if (!JobEmbeddingSupport.usable(job, contentHash)) {
            status = "CANCELLED";
            error = "Job is inactive, expired or its content changed";
        } else if (db.hasCurrentEmbedding(jobId, inputHash, embeddingProvider(), ai.embeddingModel())) {
            db.refreshProvenance(jobId, inputHash, contentHash, embeddingProvider(), ai.embeddingModel());
            status = "COMPLETED";
        }

        return db.enqueue(jobId, inputHash, requestedBy, embeddingProvider(), ai.embeddingModel(), status, error);
    }

    @Transactional
    public Map<String, Object> backfill(
        UUID actor,
        int limit) {
        if (limit < 1 || limit > 500)
            throw ApiException.badRequest("Backfill limit must be 1..500");
        if (!db.isActiveAdministrator(actor))
            throw ApiException.forbidden("Job embeddings require an active administrator");
        var jobs = db.backfillCandidates(embeddingProvider(), ai.embeddingModel(), JobEmbeddingSupport.VERSION, limit);
        List<UUID> tasks = new java.util.ArrayList<>();
        for (var job : jobs)
            tasks.add(enqueue((UUID) job.get("id"), Objects.toString(job.get("raw_content_hash"), ""), actor));
        return Map.of(
            "queued",
            tasks.size(),
            "task_ids",
            tasks,
            "worker_enabled",
            enabled,
            "provider",
            embeddingProvider(),
            "model",
            ai.embeddingModel(),
            "input_version",
            JobEmbeddingSupport.VERSION
        );
    }

    public Map<String, Object> coverage() {
        var result = new java.util.LinkedHashMap<>(db.coverage(embeddingProvider(), ai.embeddingModel()));
        result.put("provider", embeddingProvider());
        result.put("model", ai.embeddingModel());
        result.put("input_version", JobEmbeddingSupport.VERSION);
        return result;
    }

    private String embeddingProvider() {
        return Objects.requireNonNullElse(ai.embeddingProvider(), ai.provider());
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
            Optional<Map<String, Object>> claimed = transactions.execute(
                status -> db.claimNext(embeddingProvider(), ai.embeddingModel(), UUID.randomUUID(), leaseSeconds)
            );
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
            if (!JobEmbeddingSupport.usable(job, hash))
                throw ApiException.conflict("Only active, unexpired jobs can be indexed");

            return enqueue(jobId, hash, requestedBy);
        });
        Optional<Map<String, Object>> claimed = transactions.execute(
            status -> db.claimById(taskId, embeddingProvider(), ai.embeddingModel(), UUID.randomUUID(), leaseSeconds)
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
            float[] vector = ai.embedForUser(work.actor(), JobEmbeddingSupport.embeddingText(work.job()));
            transactions.executeWithoutResult(status -> {
                Map<String, Object> job = db.findJobForUpdate(work.jobId()).orElse(null);
                if (db.findLeaseForUpdate(taskId, lease).isEmpty())
                    return;
                if (job == null || !JobEmbeddingSupport.usable(job, Objects.toString(job.get("raw_content_hash"), ""))
                    || !JobEmbeddingSupport.inputHash(job).equals(work.hash())) {
                    db.cancel(taskId, lease, "Job changed or expired during embedding");
                    return;
                }

                db.replaceCurrentEmbedding(
                    work.jobId(),
                    embeddingProvider(),
                    ai.embeddingModel(),
                    work.hash(),
                    Arrays.toString(vector),
                    Objects.toString(job.get("raw_content_hash"), ""),
                    JobEmbeddingSupport.VERSION
                );
                db.complete(taskId, lease);
            });
        } catch (RuntimeException ex) {
            int attempt = ((Number) task.get("attempt_count")).intValue();
            int delay = Math.min(3600, retrySeconds * (1 << Math.min(2, Math.max(0, attempt - 1))));
            transactions.executeWithoutResult(
                status -> db.retryOrFail(taskId, lease, JobEmbeddingSupport.safeError(ex), delay)
            );
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
        if (job == null || !JobEmbeddingSupport.usable(job, Objects.toString(job.get("raw_content_hash"), ""))
            || !hash.equals(job.get("raw_content_hash")) && !hash.equals(JobEmbeddingSupport.inputHash(job))) {
            db.cancel(taskId, lease, "Job is inactive, expired or its content changed");
            return null;
        }

        UUID actor = (UUID) task.get("requested_by");
        if (actor == null || !db.isActiveAdministrator(actor)) {
            db.cancel(taskId, lease, "The requesting administrator is no longer active");
            return null;
        }
        String inputHash = JobEmbeddingSupport.inputHash(job);
        if (db.hasCurrentEmbedding(jobId, inputHash, embeddingProvider(), ai.embeddingModel())) {
            db.refreshProvenance(
                jobId,
                inputHash,
                Objects.toString(job.get("raw_content_hash"), ""),
                embeddingProvider(),
                ai.embeddingModel()
            );
            db.complete(taskId, lease);
            return null;
        }

        return new Work(jobId, actor, inputHash, job);
    }

    private record Work(
        UUID jobId,
        UUID actor,
        String hash,
        Map<String, Object> job
    ) {
    }
}
