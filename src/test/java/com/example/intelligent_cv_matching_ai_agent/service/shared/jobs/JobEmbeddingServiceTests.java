package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobEmbeddingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobEmbeddingSupport;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobEmbeddingServiceTests {

    private final JobEmbeddingRepository db = mock(JobEmbeddingRepository.class);

    private final AiService ai = mock(AiService.class);

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final AtomicBoolean transactionOpen = new AtomicBoolean();

    private final UUID jobId = UUID.randomUUID();

    private final UUID actor = UUID.randomUUID();

    private final UUID taskId = UUID.randomUUID();

    private final UUID lease = UUID.randomUUID();

    private final String hash = "a".repeat(64);

    @BeforeEach
    void transactionsAndProvider() {
        when(ai.provider()).thenReturn("openai");
        when(ai.embeddingModel()).thenReturn("test-embedding");
        when(ai.enabled()).thenReturn(true);
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation -> {
            assertFalse(transactionOpen.getAndSet(true), "Provider work must not overlap a DB transaction");
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> {
            transactionOpen.set(false);
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            transactionOpen.set(false);
            return null;
        }).when(transactionManager).rollback(any());
    }

    private JobEmbeddingService service(
        boolean enabled,
        int batchSize) {
        return new JobEmbeddingService(db, ai, transactionManager, enabled, batchSize, 300, 30);
    }

    private Map<String, Object> job() {
        return new LinkedHashMap<>(
            Map.of(
                "id",
                jobId,
                "status",
                "ACTIVE",
                "raw_content_hash",
                hash,
                "job_title",
                "Java Developer",
                "raw_content",
                "Build Spring Boot APIs"
            )
        );
    }

    private Map<String, Object> task(
        int attempt) {
        return Map.of(
            "id",
            taskId,
            "entity_id",
            jobId,
            "requested_by",
            actor,
            "lease_token",
            lease,
            "content_hash",
            hash,
            "status",
            "PROCESSING",
            "attempt_count",
            attempt
        );
    }

    private void ready(
        int attempt) {
        Map<String, Object> task = task(attempt);
        when(db.claimNext(eq("openai"), eq("test-embedding"), any(UUID.class), eq(300)))
            .thenReturn(Optional.of(task), Optional.empty());
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(job()));
        when(db.findLeaseForUpdate(taskId, lease)).thenReturn(Optional.of(task));
        when(db.isActiveAdministrator(actor)).thenReturn(true);
    }

    @Test
    void disabledWorkerLeavesDurableQueueUntouched() {
        service(false, 10).processPending();
        verifyNoInteractions(db, transactionManager);
        when(ai.embeddingsEnabled()).thenReturn(false);
        service(true, 10).processPending();
        verifyNoInteractions(db, transactionManager);
    }

    @Test
    void embeddingOnlyConfigurationDoesNotRequireAChatProvider() {
        ready(1);
        when(ai.enabled()).thenReturn(false);
        when(ai.embedForUser(eq(actor), anyString())).thenReturn(new float[1024]);

        service(true, 1).processPending();

        verify(ai).embedForUser(eq(actor), anyString());
        verify(db).complete(taskId, lease);
    }

    @Test
    void providerRunsOutsideTransactionsAndUsageBelongsToRequestingAdministrator() {
        ready(1);
        when(ai.embedForUser(eq(actor), anyString())).thenAnswer(invocation -> {
            assertFalse(transactionOpen.get());
            return new float[1024];
        });
        doAnswer(invocation -> {
            assertTrue(transactionOpen.get(), "Vector publication must be atomic with queue completion");
            return null;
        }).when(db).replaceCurrentEmbedding(
            eq(jobId),
            eq("openai"),
            eq("test-embedding"),
            eq(JobEmbeddingSupport.inputHash(job())),
            anyString(),
            eq(hash),
            eq(JobEmbeddingSupport.VERSION)
        );

        service(true, 1).processPending();

        verify(ai).embedForUser(eq(actor), contains("Java Developer"));
        verify(db).replaceCurrentEmbedding(
            eq(jobId),
            eq("openai"),
            eq("test-embedding"),
            eq(JobEmbeddingSupport.inputHash(job())),
            anyString(),
            eq(hash),
            eq(JobEmbeddingSupport.VERSION)
        );
        verify(db).complete(taskId, lease);
        verify(db, never()).retryOrFail(any(), any(), anyString(), anyInt());
        assertFalse(transactionOpen.get());
    }

    @Test
    void inactiveOrExpiredJobsAreCancelledBeforeProviderUsage() {
        ready(1);
        Map<String, Object> expired = job();
        expired.put("valid_through", OffsetDateTime.now().minusMinutes(1));
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(expired));

        service(true, 1).processPending();

        verify(db).cancel(eq(taskId), eq(lease), contains("expired"));
        verify(ai, never()).embedForUser(any(), anyString());
        verify(db, never()).replaceCurrentEmbedding(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()
        );
    }

    @Test
    void administratorSuspendedBeforeExecutionDoesNotSpendQuota() {
        ready(1);
        when(db.isActiveAdministrator(actor)).thenReturn(false);

        service(true, 1).processPending();

        verify(db).cancel(eq(taskId), eq(lease), contains("administrator"));
        verify(ai, never()).embedForUser(any(), anyString());
    }

    @Test
    void contentChangeDuringProviderCallCannotPublishAnObsoleteVector() {
        ready(1);
        Map<String, Object> changed = job();
        changed.put("raw_content_hash", "b".repeat(64));
        changed.put("parsed_requirements", "Python experience required");
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(job()), Optional.of(changed));
        when(ai.embedForUser(eq(actor), anyString())).thenReturn(new float[1024]);

        service(true, 1).processPending();

        verify(db).cancel(eq(taskId), eq(lease), contains("during embedding"));
        verify(db, never()).replaceCurrentEmbedding(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()
        );
        verify(db, never()).complete(any(), any());
    }

    @Test
    void lostLeaseCannotOverwriteAnotherWorkersVectorOrQueueState() {
        ready(1);
        when(db.findLeaseForUpdate(taskId, lease)).thenReturn(Optional.of(task(1)), Optional.empty());
        when(ai.embedForUser(eq(actor), anyString())).thenReturn(new float[1024]);

        service(true, 1).processPending();

        verify(db, never()).replaceCurrentEmbedding(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()
        );
        verify(db, never()).complete(any(), any());
        verify(db, never()).cancel(any(), any(), anyString());
    }

    @Test
    void providerFailurePreservesVisibleErrorAndExponentialBackoff() {
        ready(2);
        when(ai.embedForUser(eq(actor), anyString())).thenThrow(new ApiException(502, "Embedding unavailable"));

        service(true, 1).processPending();

        verify(db).retryOrFail(taskId, lease, "Embedding unavailable", 60);
        verify(db, never()).complete(any(), any());
        verify(db, never()).replaceCurrentEmbedding(
            any(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()
        );
    }

    @Test
    void quotaFailureIsPersistedWithoutPretendingTheWorkCompleted() {
        ready(3);
        when(ai.embedForUser(eq(actor), anyString())).thenThrow(new ApiException(429, "AI token quota exceeded"));

        service(true, 1).processPending();

        verify(db).retryOrFail(taskId, lease, "AI token quota exceeded", 120);
        verify(db, never()).complete(any(), any());
    }

    @Test
    void expiredFinalAttemptIsNotSentToProviderAgain() {
        when(db.claimNext(eq("openai"), eq("test-embedding"), any(UUID.class), eq(300)))
            .thenReturn(Optional.of(Map.of("id", taskId, "status", "FAILED")));

        service(true, 1).processPending();

        verify(db, never()).findJobForUpdate(any());
        verify(ai, never()).embedForUser(any(), anyString());
    }

    @Test
    void cachedCurrentEmbeddingCompletesClaimWithoutPaidCall() {
        ready(1);
        when(db.hasCurrentEmbedding(jobId, JobEmbeddingSupport.inputHash(job()), "openai", "test-embedding"))
            .thenReturn(true);

        service(true, 1).processPending();

        verify(db).complete(taskId, lease);
        verify(ai, never()).embedForUser(any(), anyString());
    }

    @Test
    void schedulerNeverClaimsBeyondConfiguredBatch() {
        ready(1);
        when(db.claimNext(eq("openai"), eq("test-embedding"), any(UUID.class), eq(300)))
            .thenReturn(Optional.of(task(1)));
        when(db.hasCurrentEmbedding(jobId, JobEmbeddingSupport.inputHash(job()), "openai", "test-embedding"))
            .thenReturn(true);

        service(true, 2).processPending();

        verify(db, times(2)).claimNext(eq("openai"), eq("test-embedding"), any(UUID.class), eq(300));
    }

    @Test
    void enqueueDeduplicatesAnAlreadyIndexedFingerprintAndRequiresAnActiveAdmin() {
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(job()));
        when(db.hasCurrentEmbedding(jobId, JobEmbeddingSupport.inputHash(job()), "openai", "test-embedding"))
            .thenReturn(true);
        when(
            db.enqueue(
                jobId,
                JobEmbeddingSupport.inputHash(job()),
                actor,
                "openai",
                "test-embedding",
                "COMPLETED",
                null
            )
        ).thenReturn(taskId);

        assertEquals(taskId, service(true, 1).enqueue(jobId, hash, actor));
        verify(ai, never()).embedForUser(any(), anyString());
        when(db.isActiveAdministrator(actor)).thenReturn(false);
        assertEquals(
            403,
            assertThrows(ApiException.class, () -> service(true, 1).enqueue(jobId, hash, actor)).status()
        );
    }

    @Test
    void manualIndexUsesAScopedClaimAndReusesCurrentVector() {
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(job()));
        when(db.hasCurrentEmbedding(jobId, JobEmbeddingSupport.inputHash(job()), "openai", "test-embedding"))
            .thenReturn(true);
        when(
            db.enqueue(
                jobId,
                JobEmbeddingSupport.inputHash(job()),
                actor,
                "openai",
                "test-embedding",
                "COMPLETED",
                null
            )
        ).thenReturn(taskId);
        when(db.claimById(eq(taskId), eq("openai"), eq("test-embedding"), any(UUID.class), eq(300)))
            .thenReturn(Optional.empty());
        when(db.task(taskId)).thenReturn(Map.of("id", taskId, "status", "COMPLETED"));

        assertEquals("COMPLETED", service(true, 1).indexNow(jobId, actor).get("status"));
        verify(ai, never()).embedForUser(any(), anyString());
        verify(db, never()).claimNext(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void backfillUsesTheActualEmbeddingProviderAndOnlyQueuesWork() {
        when(ai.embeddingProvider()).thenReturn("gemini");
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.backfillCandidates("gemini", "test-embedding", JobEmbeddingSupport.VERSION, 50))
            .thenReturn(java.util.List.of(Map.of("id", jobId, "raw_content_hash", hash)));
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(job()));
        when(db.enqueue(jobId, JobEmbeddingSupport.inputHash(job()), actor, "gemini", "test-embedding", "QUEUED", null))
            .thenReturn(taskId);

        var response = service(false, 1).backfill(actor, 50);

        assertEquals(1, response.get("queued"));
        assertEquals(false, response.get("worker_enabled"));
        verify(ai, never()).embedForUser(any(), anyString());
        verify(ai, never()).embed(anyString());
    }

    @Test
    void aSalaryOnlyChangeRefreshesSourceProvenanceWithoutPayingForTheSameInputAgain() {
        var changed = job();
        changed.put("raw_content_hash", "b".repeat(64));
        changed.put("salary_text", "3000 USD");
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.findJobForUpdate(jobId)).thenReturn(Optional.of(changed));
        String inputHash = JobEmbeddingSupport.inputHash(job());
        assertEquals(inputHash, JobEmbeddingSupport.inputHash(changed));
        when(db.hasCurrentEmbedding(jobId, inputHash, "openai", "test-embedding")).thenReturn(true);

        service(true, 1).enqueue(jobId, "b".repeat(64), actor);

        verify(db).refreshProvenance(jobId, inputHash, "b".repeat(64), "openai", "test-embedding");
        verify(db).enqueue(jobId, inputHash, actor, "openai", "test-embedding", "COMPLETED", null);
        verify(ai, never()).embedForUser(any(), anyString());
    }
}
