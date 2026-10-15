package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobPostingParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobUrlDiscovery;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator.validateSourceUrl;

/** Bounded source-restricted ingestion with isolated item transactions and durable monitoring. */
@Service
public class JobCrawlService {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final JobCrawlRepository db;

    private final CurrentUser user;

    private final JobService jobs;

    private final TransactionTemplate tx;

    public JobCrawlService(
        JobCrawlRepository db,
        CurrentUser user,
        JobService jobs,
        PlatformTransactionManager transactions) {
        this.db = db;
        this.user = user;
        this.jobs = jobs;
        tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> crawl(
        CrawlRequest input) {
        user.requireRole("ADMIN");
        return collect(input, user.id(), false);
    }

    /** Used by the scheduler, without manufacturing a web authentication context. */
    public Map<String, Object> crawlAs(
        CrawlRequest input,
        UUID actor) {
        db.requireActiveAdmin(actor);
        return collect(input, actor, true);
    }

    private Map<String, Object> collect(
        CrawlRequest input,
        UUID actor,
        boolean scheduled) {
        if (input.effectiveLimit() < 1 || input.effectiveLimit() > 20 || input.effectiveMaxPages() < 1
            || input.effectiveMaxPages() > 5)
            throw ApiException.badRequest("limit must be 1..20 and maxPages must be 1..5");
        String site = text(input.sourceSite()).strip().toUpperCase(Locale.ROOT);
        // Validate the source and all explicit URLs before opening a run or accessing the network.
        JobUrlDiscovery.searchUrl(site, input.keyword(), input.location(), 1);
        List<String> urls = (input.urls() == null ? List.<String>of() : input.urls()).stream()
            .map(url -> JobUrlDiscovery.canonicalUrl(site, url)).distinct().toList();
        if (urls.size() > 20)
            throw ApiException.badRequest("At most 20 detail URLs are allowed");
        int requestedLimit = urls.isEmpty() ? input.effectiveLimit() : urls.size();
        UUID run = tx.execute(
            status -> db.insertMarketCrawlRuns(
                fields(
                    "source_site",
                    site,
                    "keyword",
                    input.keyword().strip(),
                    "role_category",
                    input.roleCategory().strip(),
                    "requested_location",
                    input.location(),
                    "requested_limit",
                    requestedLimit,
                    "requested_by",
                    actor,
                    "status",
                    "RUNNING",
                    "candidate_urls_discovered",
                    0,
                    "started_at",
                    OffsetDateTime.now()
                )
            )
        );
        int searchPages = 0, rejected = 0;
        if (urls.isEmpty()) {
            Set<String> found = new LinkedHashSet<>();
            for (int pageNumber = 1; pageNumber <= input.effectiveMaxPages()
                && found.size() < input.effectiveLimit(); pageNumber++) {
                String searchUrl = JobUrlDiscovery.searchUrl(site, input.keyword(), input.location(), pageNumber);
                try {
                    HttpPage page = fetch(site, searchUrl);
                    searchPages++;
                    if (page.status() < 200 || page.status() >= 300)
                        throw new IOException("Search returned HTTP " + page.status());
                    List<String> links =
                        JobUrlDiscovery.discoverLinks(page.html(), site, page.url(), input.effectiveLimit());
                    int before = found.size();
                    for (String link : links) {
                        if (found.size() >= input.effectiveLimit())
                            break;
                        found.add(link);
                    }
                    recordItem(
                        run,
                        page.url(),
                        "SEARCH_FETCHED",
                        page.status(),
                        null,
                        null,
                        "Discovered " + (found.size() - before) + " new detail URLs"
                    );
                    if (found.size() == before)
                        break;
                } catch (IOException | ApiException ex) {
                    recordItem(run, searchUrl, "SEARCH_FETCH_FAILED", null, null, null, ex.getMessage());
                    rejected++;
                    break;
                }
            }
            urls = List.copyOf(found);
        }
        int fetched = 0, parsed = 0, imported = 0, duplicates = 0, wrongRegion = 0, importedSkills = 0;
        for (String requestedUrl : urls) {
            Integer httpStatus = null;
            String title = null, hash = null, finalUrl = requestedUrl;
            try {
                HttpPage page = fetch(site, requestedUrl);
                fetched++;
                httpStatus = page.status();
                finalUrl = JobUrlDiscovery.canonicalUrl(site, page.url());
                if (httpStatus == 404 || httpStatus == 410) {
                    expireSource(site, requestedUrl);
                    if (!requestedUrl.equals(finalUrl))
                        expireSource(site, finalUrl);
                    recordItem(
                        run,
                        finalUrl,
                        "EXPIRED",
                        httpStatus,
                        null,
                        null,
                        "Source returned HTTP " + httpStatus + "; source marked expired"
                    );
                    rejected++;
                    continue;
                }
                if (httpStatus < 200 || httpStatus >= 300)
                    throw ApiException.badRequest("Source returned HTTP " + httpStatus);
                var posting = JobPostingParser.parse(page.html(), site, finalUrl, input.roleCategory())
                    .orElseThrow(() -> ApiException.badRequest("No supported job detail data found"));
                parsed++;
                title = posting.job().jobTitle();
                hash = contentHash(posting.job());
                if (posting.job().validThrough() != null
                    && !posting.job().validThrough().isAfter(OffsetDateTime.now())) {
                    expireSource(site, finalUrl);
                    recordItem(
                        run,
                        finalUrl,
                        "EXPIRED",
                        httpStatus,
                        title,
                        hash,
                        "Source validThrough has elapsed; not imported"
                    );
                    rejected++;
                    continue;
                }
                if (!JobNormalizer.matchesLocation(posting.job().location(), input.location())) {
                    recordItem(
                        run,
                        finalUrl,
                        "WRONG_REGION",
                        httpStatus,
                        title,
                        hash,
                        "Source location does not match the requested location"
                    );
                    wrongRegion++;
                    rejected++;
                    continue;
                }
                int responseStatus = httpStatus;
                boolean duplicate = Boolean.TRUE.equals(tx.execute(status -> {
                    boolean existing = db.findMarketJobPostsByRawContentHash(contentHash(posting.job())).isPresent();
                    Map<String, Object> saved =
                        scheduled ? jobs.importJobAs(posting.job(), actor) : jobs.importJob(posting.job());
                    boolean reused = existing || Boolean.TRUE.equals(saved.get("deduplicated"));
                    if (!reused)
                        db.updateParsedMetadata(posting.quality(), OffsetDateTime.now(), saved.get("id"));
                    insertItem(
                        run,
                        posting.job().sourceUrl(),
                        reused ? "DUPLICATE" : "IMPORTED",
                        responseStatus,
                        posting.job().jobTitle(),
                        contentHash(posting.job()),
                        reused ? "Existing job reused; source refreshed" : "Imported; embedding queued automatically"
                    );
                    return reused;
                }));
                if (duplicate)
                    duplicates++;
                else {
                    imported++;
                    importedSkills += posting.job().skills().size();
                }
            } catch (IOException ex) {
                if (ex instanceof FetchFailure failure) {
                    httpStatus = failure.httpStatus;
                    if (httpStatus != null)
                        fetched++;
                }
                recordItem(run, finalUrl, "FETCH_FAILED", httpStatus, title, hash, ex.getMessage());
                rejected++;
            } catch (ApiException ex) {
                recordItem(run, finalUrl, "REJECTED", httpStatus, title, hash, ex.getMessage());
                rejected++;
            } catch (RuntimeException ex) {
                recordItem(
                    run,
                    finalUrl,
                    "IMPORT_FAILED",
                    httpStatus,
                    title,
                    hash,
                    "Import failed: " + ex.getClass().getSimpleName()
                );
                rejected++;
            }
        }
        String outcome = rejected == 0 ? "COMPLETED" : imported + duplicates == 0 ? "FAILED" : "PARTIAL";
        int fetchedCount = fetched, parsedCount = parsed, importedCount = imported, duplicateCount = duplicates,
            rejectedCount = rejected, wrongRegionCount = wrongRegion, searchCount = searchPages,
            discoveredCount = input.urls() == null || input.urls().isEmpty() ? urls.size() : 0,
            skillCount = importedSkills, checkedCount = urls.size();
        tx.executeWithoutResult(status -> {
            db.updateMarketCrawlRunsById(
                outcome,
                fetchedCount,
                parsedCount,
                importedCount,
                duplicateCount,
                checkedCount,
                wrongRegionCount,
                rejectedCount,
                "Imported " + importedCount + "; duplicates " + duplicateCount + "; rejected " + rejectedCount,
                OffsetDateTime.now(),
                run
            );
            db.updateDiscoveryCounts(searchCount, discoveredCount, skillCount, run);
            db.insertAuditLogs(
                fields(
                    "actor_user_id",
                    actor,
                    "action",
                    "JOB_CRAWL",
                    "entity_type",
                    "market_crawl_runs",
                    "entity_id",
                    run
                )
            );
        });
        var result = db.getMarketCrawlRunsById(run);
        result.put("items", db.listMarketCrawlItemsByCrawlRunId(run));
        return result;
    }

    private void expireSource(
        String site,
        String url) {
        tx.executeWithoutResult(status -> {
            db.readDatabaseValue(url);
            db.expireSource(site, url);
            db.updateMarketJobEmbeddingsByMarketJobId(site, url);
            db.updateMarketJobPostsByUpper(site, url);
        });
    }

    public List<Map<String, Object>> runs(
        int limit,
        int offset) {
        user.requireRole("ADMIN");
        return db.listMarketCrawlRuns(pageSize(limit), offset(offset));
    }

    public Map<String, Object> run(
        UUID id) {
        user.requireRole("ADMIN");
        var result = db.getMarketCrawlRunsById(id);
        result.put("items", db.listMarketCrawlItemsByCrawlRunId(id));
        return result;
    }

    private void recordItem(
        UUID run,
        String url,
        String outcome,
        Integer status,
        String title,
        String hash,
        String message) {
        tx.executeWithoutResult(ignored -> insertItem(run, url, outcome, status, title, hash, message));
    }

    private void insertItem(
        UUID run,
        String url,
        String outcome,
        Integer status,
        String title,
        String hash,
        String message) {
        db.insertMarketCrawlItems(
            fields(
                "crawl_run_id",
                run,
                "source_url",
                url,
                "item_status",
                outcome,
                "http_status",
                status,
                "job_title",
                title,
                "raw_content_hash",
                hash,
                "message",
                message == null ? null : message.substring(0, Math.min(1000, message.length())),
                "created_at",
                OffsetDateTime.now()
            )
        );
    }

    record HttpPage(
        String url,
        int status,
        String html
    ) {
    }

    protected HttpPage fetch(
        String source,
        String url)
        throws IOException {
        URI current = validateSourceUrl(source, url);
        Integer lastResponseStatus = null;

        for (int redirects = 0; redirects <= 3; redirects++) {
            Connection.Response response;

            try {
                response = Jsoup.connect(current.toASCIIString()).followRedirects(false).ignoreHttpErrors(true)
                    .ignoreContentType(true).timeout(5000).maxBodySize(MAX_BODY_BYTES + 1)
                    .userAgent("EduNexus Job Import/1.0").header("Accept", "text/html,application/xhtml+xml")
                    .method(Connection.Method.GET).execute();
            } catch (IOException ex) {
                if (lastResponseStatus != null)
                    throw new FetchFailure(
                        "Connection failed after a source redirect: " + ex.getClass().getSimpleName(),
                        lastResponseStatus
                    );

                throw ex;
            }

            int status = response.statusCode();
            lastResponseStatus = status;

            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                if (redirects == 3)
                    throw new FetchFailure("Source exceeded three redirects", status);

                String target = response.header("Location");
                if (target == null || target.isBlank())
                    throw new FetchFailure("Source redirect has no Location", status);

                try {
                    current = validateSourceUrl(source, current.resolve(target).toString());
                } catch (IllegalArgumentException | ApiException ex) {
                    throw new FetchFailure("Source redirected outside the permitted source URLs", status);
                }

                continue;
            }

            if (response.bodyAsBytes().length > MAX_BODY_BYTES)
                throw new FetchFailure("Source response exceeds 2 MiB", status);
            if (status < 200 || status >= 300)
                return new HttpPage(current.toASCIIString(), status, "");

            String type = response.contentType();
            if (type == null || !(type.toLowerCase(Locale.ROOT).startsWith("text/html")
                || type.toLowerCase(Locale.ROOT).startsWith("application/xhtml+xml")))
                throw new FetchFailure("Source did not return HTML", status);

            return new HttpPage(current.toASCIIString(), status, response.body());
        }

        throw new FetchFailure("Source exceeded three redirects", null);
    }

    private static class FetchFailure extends IOException {

        private final Integer httpStatus;

        FetchFailure(
            String message,
            Integer httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    record ParsedJob(
        ImportJobRequest job,
        OffsetDateTime postedAt,
        OffsetDateTime validThrough
    ) {
    }

    static Optional<ParsedJob> parseJobPosting(
        String html,
        String source,
        String url,
        String roleCategory) {
        return JobPostingParser.parse(html, source, url, roleCategory)
            .map(parsed -> new ParsedJob(parsed.job(), parsed.job().postedAt(), parsed.job().validThrough()));
    }

    static String contentHash(
        ImportJobRequest job) {
        return JobNormalizer.contentHash(job);
    }
}
