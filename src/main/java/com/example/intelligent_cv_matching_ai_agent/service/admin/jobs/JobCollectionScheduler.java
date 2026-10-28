package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCollectionRepository;
import com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.JobCollectionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class JobCollectionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(JobCollectionScheduler.class);

    private static final Duration COLLECTION_LEASE = Duration.ofMinutes(15);

    private static final Duration EXPIRATION_LEASE = Duration.ofMinutes(5);

    private final CrawlSettingsService settings;

    private final JobCollectionRepository db;

    private final JobCrawlService crawler;

    private final boolean expirationEnabled;

    private final Duration expirationDelay;

    private final int expirationBatchSize;

    public JobCollectionScheduler(
        CrawlSettingsService settings,
        JobCollectionRepository db,
        JobCrawlService crawler,
        @Value("${app.jobs.expiration.enabled:true}")
        boolean expirationEnabled,
        @Value("${app.jobs.expiration.fixed-delay:PT15M}")
        Duration expirationDelay,
        @Value("${app.jobs.expiration.batch-size:500}")
        int expirationBatchSize) {
        this.settings = settings;
        this.db = db;
        this.crawler = crawler;
        this.expirationEnabled = expirationEnabled;
        this.expirationDelay = expirationDelay;
        this.expirationBatchSize = Math.max(1, Math.min(expirationBatchSize, 1000));
        if (expirationDelay == null || expirationDelay.isNegative() || expirationDelay.isZero()) {
            throw new IllegalArgumentException("Job expiration fixed-delay must be positive");
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void collect() {
        var configuration = settings.effective();
        if (!configuration.enabled()) {
            return;
        }
        UUID actor = configuration.actorId();
        if (!db.isActiveAdministrator(actor)) {
            LOG.warn("Scheduled collection skipped: actor-id must identify an active administrator");
            return;
        }
        Duration interval = Duration.parse(configuration.fixedDelay());
        List<CrawlSourceSettingsResponse> sources = List.copyOf(configuration.sources());
        for (int index = 0; index < sources.size(); index++) {
            if (index > 0) {
                var current = settings.effective();
                if (!current.enabled() || current.version() != configuration.version()) {
                    break;
                }
            }
            collectSource(sources.get(index), actor, interval);
        }
    }

    private void collectSource(
        CrawlSourceSettingsResponse source,
        UUID actor,
        Duration interval) {
        String name = JobCollectionSupport.leaseName(source);
        UUID token = UUID.randomUUID();
        if (!db.acquireCollectionLease(name, token, COLLECTION_LEASE, interval)) {
            return;
        }
        String error = null;
        try {
            Map<String, Object> result = crawler.crawlAs(
                new CrawlRequest(
                    source.sourceSite(),
                    source.keyword(),
                    source.roleCategory(),
                    source.location(),
                    List.of(),
                    source.limit(),
                    source.maxPages()
                ),
                actor
            );
            String status = Objects.toString(result.get("status"), "");
            if ("FAILED".equals(status) || "PARTIAL".equals(status)) {
                error = "Crawl " + result.get("id") + " ended with status " + status;
            }
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "");
            LOG.warn("Scheduled collection failed for {}: {}", name, error);
        } finally {
            db.completeLease(name, token, error);
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.expiration.fixed-delay:PT15M}")
    public void expire() {
        if (!expirationEnabled) {
            return;
        }
        String name = "job-expiration";
        UUID token = UUID.randomUUID();
        if (!db.acquireLease(name, token, EXPIRATION_LEASE, expirationDelay)) {
            return;
        }
        String error = null;
        try {
            JobCollectionRepository.ExpirationResult result = db.expireSources(expirationBatchSize);
            if (result.expiredSources() > 0 || result.expiredJobs() > 0) {
                LOG.info(
                    "Job expiration: {} sources, {} canonical jobs, {} vectors",
                    result.expiredSources(),
                    result.expiredJobs(),
                    result.invalidatedEmbeddings()
                );
            }
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), "");
            LOG.warn("Scheduled job expiration failed: {}", error);
        } finally {
            db.completeLease(name, token, error);
        }
    }

    static String leaseName(
        CrawlSourceSettingsResponse source) {
        return JobCollectionSupport.leaseName(source);
    }
}
