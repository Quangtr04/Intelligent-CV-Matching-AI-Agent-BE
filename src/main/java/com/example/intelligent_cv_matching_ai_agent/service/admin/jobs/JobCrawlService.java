package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator.validateSourceUrl;

/**
 * Explicit administrator ingestion; requests never follow links or invoke AI
 * automatically.
 */
@Service
public class JobCrawlService {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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

        // Validate every URL before opening a run or making a network request.
        List<String> urls = input.urls().stream().map(url -> validateSourceUrl(input.sourceSite(), url).toASCIIString())
            .distinct().toList();

        UUID run = tx.execute(
            status -> db.insertMarketCrawlRuns(
                fields(
                    "source_site",
                    input.sourceSite(),
                    "keyword",
                    input.keyword().strip(),
                    "role_category",
                    input.roleCategory().strip(),
                    "requested_location",
                    input.location(),
                    "requested_limit",
                    urls.size(),
                    "status",
                    "RUNNING",
                    "candidate_urls_discovered",
                    0,
                    "started_at",
                    OffsetDateTime.now()
                )
            )
        );

        int fetched = 0, parsed = 0, imported = 0, duplicates = 0, rejected = 0, wrongRegion = 0;

        for (String requestedUrl : urls) {
            Integer httpStatus = null;
            String title = null;
            String hash = null;
            String finalUrl = requestedUrl;

            try {
                HttpPage page = fetch(input.sourceSite(), requestedUrl);
                fetched++;
                httpStatus = page.status();
                finalUrl = page.url();
                if (httpStatus < 200 || httpStatus >= 300)
                    throw ApiException.badRequest("Source returned HTTP " + httpStatus);

                ParsedJob posting =
                    parseJobPosting(page.html(), input.sourceSite(), finalUrl, input.roleCategory()).orElseThrow(
                        () -> ApiException
                            .badRequest("No supported schema.org JobPosting JSON-LD was found; use manual job import")
                    );
                parsed++;
                title = posting.job().jobTitle();
                hash = contentHash(posting.job());

                if (posting.validThrough() != null && !posting.validThrough().isAfter(OffsetDateTime.now())) {
                    String expiredUrl = finalUrl;

                    tx.executeWithoutResult(status -> {
                        db.readDatabaseValue(expiredUrl);

                        db.updateMarketJobEmbeddingsByMarketJobId(input.sourceSite(), expiredUrl);
                        db.updateMarketJobPostsByUpper(input.sourceSite(), expiredUrl);
                    });
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

                if (input.location() != null && !input.location().isBlank()
                    && !fold(posting.job().location()).contains(fold(input.location()))) {
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
                    db.readDatabaseValue(posting.job().sourceUrl());
                    boolean existing = db.findMarketJobPostsByRawContentHash(contentHash(posting.job())).isPresent();
                    Map<String, Object> saved = jobs.importJob(posting.job());

                    if (!existing)
                        db.updateMarketJobPostsById(posting.postedAt(), OffsetDateTime.now(), saved.get("id"));

                    insertItem(
                        run,
                        posting.job().sourceUrl(),
                        existing ? "DUPLICATE" : "IMPORTED",
                        responseStatus,
                        posting.job().jobTitle(),
                        contentHash(posting.job()),
                        existing
                            ? "Unchanged active job already imported"
                            : "Imported schema.org JobPosting; awaiting explicit vector indexing"
                    );

                    return existing;
                }));

                if (duplicate)
                    duplicates++;
                else
                    imported++;
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
                // An import rolls back its own item transaction; its failure remains
                // independently visible.
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
            rejectedCount = rejected, wrongRegionCount = wrongRegion;

        tx.executeWithoutResult(status -> {
            db.updateMarketCrawlRunsById(
                outcome,
                fetchedCount,
                parsedCount,
                importedCount,
                duplicateCount,
                urls.size(),
                wrongRegionCount,
                rejectedCount,
                "Processed " + urls.size() + " supplied URLs; inspect item outcomes for details",
                OffsetDateTime.now(),
                run
            );
            db.insertAuditLogs(
                fields(
                    "actor_user_id",
                    user.id(),
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
                message,
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
        List<Map<String, Object>> postings = new ArrayList<>();

        for (var script : Jsoup.parse(html).select("script[type=application/ld+json]")) {
            try {
                findJobPostings(JSON.readValue(script.data(), Object.class), postings, 0);
            } catch (RuntimeException ex) {
                /* A malformed unrelated JSON-LD block cannot hide a valid following block. */ }
        }

        if (postings.isEmpty())
            return Optional.empty();
        if (postings.size() > 1)
            throw ApiException.badRequest("Expected one JobPosting per supplied detail page");

        Map<String, Object> posting = postings.getFirst();
        String title = requiredText(Jsoup.parse(textValue(posting.get("title"))).text(), 255, "title");
        String description = requiredText(posting.get("description"), 100000, "description");
        String plainDescription = Jsoup.parse(description).text().strip();
        if (plainDescription.length() < 30)
            throw ApiException.badRequest("JobPosting description must contain at least 30 characters");

        String company = optionalText(asMap(posting.get("hiringOrganization")).get("name"), 255);
        String location = locations(posting.get("jobLocation"));

        if (location.isBlank() && "TELECOMMUTE".equals(textValue(posting.get("jobLocationType"))))
            location = "Remote";

        return Optional.of(
            new ParsedJob(
                new ImportJobRequest(
                    source,
                    url,
                    title,
                    company,
                    location,
                    salary(posting),
                    roleCategory.strip(),
                    description,
                    List.of()
                ),
                date(posting.get("datePosted"), false),
                date(posting.get("validThrough"), true)
            )
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(
        Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static void findJobPostings(
        Object value,
        List<Map<String, Object>> postings,
        int depth) {
        if (depth > 10)
            return;

        if (value instanceof List<?> list) {
            for (Object item : list)
                findJobPostings(item, postings, depth + 1);
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> object = asMap(value);
            Object type = object.get("@type");
            List<?> types = type instanceof List<?> list ? list : Collections.singletonList(type);

            if (types.stream().anyMatch(
                t -> Set.of("JobPosting", "https://schema.org/JobPosting", "http://schema.org/JobPosting")
                    .contains(textValue(t))
            ))
                postings.add(object);

            if (object.containsKey("@graph"))
                findJobPostings(object.get("@graph"), postings, depth + 1);
        }
    }

    private static String requiredText(
        Object value,
        int max,
        String field) {
        String result = textValue(value).strip();
        if (result.isBlank() || result.length() > max)
            throw ApiException.badRequest("JobPosting " + field + " is missing or too long");

        return result;
    }

    private static String optionalText(
        Object value,
        int max) {
        String result = Jsoup.parse(textValue(value)).text().strip();

        return result.length() > max ? result.substring(0, max) : result;
    }

    private static String textValue(
        Object value) {
        return value instanceof String || value instanceof Number ? value.toString() : "";
    }

    private static String locations(
        Object value) {
        List<?> entries = value instanceof List<?> list ? list : Collections.singletonList(value);
        Set<String> locations = new LinkedHashSet<>();

        for (Object entry : entries) {
            var place = asMap(entry);
            var address = asMap(place.get("address"));
            List<String> parts = new ArrayList<>();

            for (String key : List.of("addressLocality", "addressRegion", "addressCountry")) {
                Object part = address.get(key);
                String name = optionalText(part instanceof Map<?, ?> ? asMap(part).get("name") : part, 120);

                if (!name.isBlank() && !parts.contains(name))
                    parts.add(name);
            }

            String location = parts.isEmpty() ? optionalText(place.get("name"), 255) : String.join(", ", parts);

            if (!location.isBlank())
                locations.add(location);
        }

        return optionalText(String.join("; ", locations), 255);
    }

    private static String salary(
        Map<String, Object> posting) {
        Object base = posting.get("baseSalary");
        if (base instanceof String || base instanceof Number)
            return optionalText(base, 255);

        var amount = asMap(base);
        Object value = amount.get("value");
        var range = asMap(value);
        String number = textValue(value);

        if (number.isBlank()) {
            number = textValue(range.get("value"));

            if (number.isBlank())
                number = String.join(" - ", List.of(textValue(range.get("minValue")), textValue(range.get("maxValue"))))
                    .replaceAll("^ - | - $", "");
        }

        if (number.isBlank())
            return "";

        return optionalText(
            number + " " + textValue(amount.getOrDefault("currency", posting.get("salaryCurrency")))
                + (textValue(range.get("unitText")).isBlank() ? "" : " / " + textValue(range.get("unitText"))),
            255
        );
    }

    private static OffsetDateTime date(
        Object value,
        boolean endOfDay) {
        String date = textValue(value);
        if (date.isBlank())
            return null;

        try {
            return OffsetDateTime.parse(date);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(date).atZone(SOURCE_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    LocalDate day = LocalDate.parse(date);

                    return (endOfDay ? day.plusDays(1) : day).atStartOfDay(SOURCE_ZONE).toOffsetDateTime();
                } catch (DateTimeParseException ex) {
                    throw ApiException.badRequest("JobPosting contains an invalid date");
                }
            }
        }
    }

    static String contentHash(
        ImportJobRequest job) {
        return FlowSupport.hash(
            job.sourceSite() + "\n" + job.sourceUrl() + "\n" + job.jobTitle() + "\n"
                + Jsoup.parse(job.content()).text().strip()
        );
    }

    private static String fold(
        String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replace('đ', 'd').strip();
    }
}
