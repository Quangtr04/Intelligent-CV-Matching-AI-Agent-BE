package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobSourceValidator;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobCrawlServiceTests {

    private static final String URL = "https://itviec.com/it-jobs/java-fixture";

    @ParameterizedTest
    @CsvSource(
        value = {
            "Hanoi|Hà Nội|true",
            "DaNang|Đà Nẵng|true",
            "CanTho|Cần Thơ|true",
            "Remote|Remote|true",
            "Da Lat|Đà Lạt|true",
            "Hanoi|Ho Chi Minh|false",
            "Đà Nẵng|Hà Nội|false",
            "Hanoi|Ho Chi Minh; Ha Noi|true"},
        delimiter = '|'
    )
    void explicitCrawlAcceptsTheChosenLocationAndRejectsOtherRegions(
        String requested,
        String actual,
        boolean accepted) {
        SqlStore db = mock(SqlStore.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenReturn(Map.of("id", UUID.randomUUID()));
        JobCrawlService service =
            new JobCrawlService(new JobCrawlRepository(db), mock(CurrentUser.class), jobs, transactions()) {

                @Override
                protected HttpPage fetch(
                    String source,
                    String url) {
                    return new HttpPage(url, 200, locationPosting(actual));
                }
            };
        service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", requested, List.of(URL)));
        verify(jobs, times(accepted ? 1 : 0)).importJob(any());
        if (accepted) {
            verify(jobs).importJob(argThat(input -> ZoneOffset.ofHours(7).equals(input.validThrough().getOffset())));
        }
        verify(db).insert(
            eq("market_crawl_items"),
            argThat(item -> (accepted ? "IMPORTED" : "WRONG_REGION").equals(item.get("item_status")))
        );
        verify(db).update(
            contains("UPDATE market_crawl_runs SET status="),
            eq(accepted ? "COMPLETED" : "FAILED"),
            eq(1),
            eq(1),
            eq(accepted ? 1 : 0),
            eq(0),
            eq(1),
            eq(accepted ? 0 : 1),
            eq(accepted ? 0 : 1),
            anyString(),
            any(),
            eq(run)
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Toàn quốc", "All Cities"})
    void noSelectedLocationImportsJobsAcrossRegions(
        String requested) {
        SqlStore db = mock(SqlStore.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenReturn(Map.of("id", UUID.randomUUID()));
        List<String> locations = List.of("Ho Chi Minh", "Hà Nội", "Đà Nẵng", "Đà Lạt", "Remote");
        List<String> urls = new ArrayList<>();
        for (int index = 0; index < locations.size(); index++) {
            urls.add(URL + "-" + index);
        }
        new JobCrawlService(new JobCrawlRepository(db), mock(CurrentUser.class), jobs, transactions()) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                return new HttpPage(url, 200, locationPosting(locations.get(urls.indexOf(url))));
            }
        }.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", requested, urls));

        verify(jobs, times(locations.size())).importJob(any());
        verify(db, never())
            .insert(eq("market_crawl_items"), argThat(item -> "WRONG_REGION".equals(item.get("item_status"))));
    }

    @ParameterizedTest
    @CsvSource(
        value = {
            "ITVIEC|https://itviec.com/it-jobs?query=Java&page=1&city=ha-noi"
                + "|https://itviec.com/it-jobs/java-location-1234",
            "TOPCV|https://www.topcv.vn/tim-viec-lam-java?page=1&cityIds%5B%5D=1"
                + "|https://www.topcv.vn/viec-lam/java-location/1234.html"},
        delimiter = '|'
    )
    void discoveryFetchesTheSelectedCityBeforeImportingDetails(
        String source,
        String expectedSearch,
        String detail) {
        SqlStore db = mock(SqlStore.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenReturn(Map.of("id", UUID.randomUUID()));
        List<String> fetched = new ArrayList<>();
        new JobCrawlService(new JobCrawlRepository(db), mock(CurrentUser.class), jobs, transactions()) {

            @Override
            protected HttpPage fetch(
                String site,
                String url) {
                fetched.add(url);
                if (url.equals(expectedSearch))
                    return new HttpPage(url, 200, "<a href='" + detail + "'>Java in Hanoi</a>");
                if (url.equals(detail))
                    return new HttpPage(url, 200, locationPosting("Hà Nội"));
                throw new AssertionError("Unexpected source request " + url);
            }
        }.crawl(new CrawlRequest(source, "Java", "Backend", "Hanoi", null, 1, 1));

        assertEquals(List.of(expectedSearch, detail), fetched);
        verify(jobs).importJob(argThat(input -> "Ha Noi".equals(input.location())));
    }

    private static String locationPosting(
        String location) {
        String geography = "Remote".equals(location)
            ? "\"jobLocationType\":\"TELECOMMUTE\""
            : "\"jobLocation\":{\"address\":{\"addressLocality\":\"" + location + "\"}}";
        return "<script type='application/ld+json'>{\"@type\":\"JobPosting\",\"title\":\"Java Developer\","
            + "\"description\":\"Build reliable backend services using Java and support production applications.\","
            + "\"hiringOrganization\":{\"name\":\"Location Test Company\"},"
            + "\"datePosted\":\"2026-01-01\",\"validThrough\":\"2099-12-31\"," + geography + "}</script>";
    }

    private String fixture() throws IOException {
        try (var stream = getClass().getResourceAsStream("/jobs/jobposting-graph.html")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void graphFixtureExtractsOnlyJobPostingAndNormalizesMetadata() throws Exception {
        var parsed = JobCrawlService.parseJobPosting(fixture(), "ITVIEC", URL, "Backend").orElseThrow();
        assertEquals("Java Backend Developer", parsed.job().jobTitle());
        assertEquals("EduNexus Engineering", parsed.job().companyName());
        assertEquals("Ho Chi Minh; Ha Noi", parsed.job().location());
        assertEquals("20000000 - 35000000 VND / MONTH", parsed.job().salaryText());
        assertEquals(ZoneOffset.ofHours(7), parsed.postedAt().getOffset());
        assertEquals(1, parsed.postedAt().getDayOfMonth());
        // A date-only expiry remains valid throughout that day in the source timezone.
        assertEquals(2100, parsed.validThrough().getYear());
        assertEquals(1, parsed.validThrough().getDayOfYear());
        var sameText = new ImportJobRequest(
            "ITVIEC",
            URL,
            parsed.job().jobTitle(),
            null,
            null,
            null,
            "Backend",
            ("<div>Build reliable Java and SQL backend services.</div><p>Work with " + "Spring and Docker.</p>"),
            List.of()
        );
        assertNotEquals(JobCrawlService.contentHash(parsed.job()), JobCrawlService.contentHash(sameText));
        var sameMetadata = new ImportJobRequest(
            "ITVIEC",
            URL,
            parsed.job().jobTitle(),
            parsed.job().companyName(),
            parsed.job().location(),
            parsed.job().salaryText(),
            "Backend",
            ("<div>Build reliable Java and SQL backend services.</div><p>Work with " + "Spring and Docker.</p>"),
            parsed.job().skills(),
            parsed.job().employmentType(),
            parsed.job().requirements(),
            parsed.job().responsibilities(),
            parsed.job().benefits(),
            parsed.job().postedAt(),
            parsed.job().validThrough()
        );
        assertEquals(JobCrawlService.contentHash(parsed.job()), JobCrawlService.contentHash(sameMetadata));
    }

    @Test
    void arrayPostingSupportsRemoteAndMalformedUnrelatedScripts() {
        String html = """
                <script type="application/ld+json">{malformed}</script>
                <script type="application/ld+json">[
                  {"@type":"WebSite"},
                  {"@type":"https://schema.org/JobPosting","title":"Remote Java Developer",
                   "description":"Build reliable Java applications and maintain production backend services.",
                   "jobLocationType":"TELECOMMUTE","datePosted":"2026-10-01T08:30:00Z"}
                ]</script>
                """;
        var parsed = JobCrawlService.parseJobPosting(html, "ITVIEC", URL, "Backend").orElseThrow();
        assertEquals("Remote", parsed.job().location());
        assertEquals(ZoneOffset.UTC, parsed.postedAt().getOffset());
        assertNull(parsed.validThrough());
    }

    @Test
    void missingAmbiguousAndInvalidMetadataNeverProduceInventedJob() {
        assertTrue(JobCrawlService.parseJobPosting("<h1>Java Developer</h1>", "ITVIEC", URL, "Backend").isEmpty());
        String posting = ("{\"@type\":\"JobPosting\",\"title\":\"Java\",\"description\":\"Build "
            + "reliable Java applications and production backend services.\"}");
        assertThrows(
            ApiException.class,
            () -> JobCrawlService.parseJobPosting(
                "<script type='application/ld+json'>[" + posting + "," + posting + "]</script>",
                "ITVIEC",
                URL,
                "Backend"
            )
        );
        assertThrows(
            ApiException.class,
            () -> JobCrawlService.parseJobPosting(
                "<script type='application/ld+json'>"
                    + posting.replace("\"title\"", "\"validThrough\":\"tomorrow\",\"title\"") + "</script>",
                "ITVIEC",
                URL,
                "Backend"
            )
        );
    }

    @Test
    void sourceValidationRejectsMismatchesAndRedirectDestinations() {
        assertThrows(ApiException.class, () -> JobSourceValidator.validateSourceUrl("ITVIEC", "https://topcv.vn/job"));
        assertThrows(
            ApiException.class,
            () -> JobSourceValidator.validateSourceUrl("TOPCV", "https://topcv.vn.attacker.test/job")
        );
        assertThrows(
            ApiException.class,
            () -> JobSourceValidator.validateSourceUrl("TOPCV", "https://custom.topcv.vn/job")
        );
        assertThrows(
            ApiException.class,
            () -> JobSourceValidator.validateSourceUrl("TOPCV", "https://topcv.vn:443/job")
        );
        assertEquals(
            "https://www.topcv.vn/jobs/java?x=%2F",
            JobSourceValidator.validateSourceUrl("TOPCV", "https://WWW.TOPCV.VN/jobs/java?x=%2F#view").toASCIIString()
        );
    }

    @Test
    void redirectOutsideSelectedSourceIsNeverFetched() throws Exception {
        Connection connection = mock(Connection.class, RETURNS_SELF);
        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(302);
        when(response.header("Location")).thenReturn("https://topcv.vn/other-source");
        when(connection.execute()).thenReturn(response);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(URL)).thenReturn(connection);
            JobCrawlService service = new JobCrawlService(
                new JobCrawlRepository(mock(SqlStore.class)),
                mock(CurrentUser.class),
                mock(JobService.class),
                transactions()
            );
            assertThrows(IOException.class, () -> service.fetch("ITVIEC", URL));
            jsoup.verify(() -> Jsoup.connect(URL), times(1));
            jsoup.verifyNoMoreInteractions();
            verify(connection).followRedirects(false);
        }
    }

    @Test
    void excessiveRedirectsStopAfterThreeAllowedHops() throws Exception {
        Connection connection = mock(Connection.class, RETURNS_SELF);
        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(301);
        when(response.header("Location")).thenReturn("/it-jobs/loop");
        when(connection.execute()).thenReturn(response);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(anyString())).thenReturn(connection);
            JobCrawlService service = new JobCrawlService(
                new JobCrawlRepository(mock(SqlStore.class)),
                mock(CurrentUser.class),
                mock(JobService.class),
                transactions()
            );
            assertThrows(IOException.class, () -> service.fetch("ITVIEC", URL));
            verify(connection, times(4)).execute();
        }
    }

    @Test
    void oversizedSourceResponseIsRejectedBeforeParsing() throws Exception {
        Connection connection = mock(Connection.class, RETURNS_SELF);
        Connection.Response response = mock(Connection.Response.class);
        when(response.statusCode()).thenReturn(200);
        when(response.bodyAsBytes()).thenReturn(new byte[2 * 1024 * 1024 + 1]);
        when(connection.execute()).thenReturn(response);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(URL)).thenReturn(connection);
            JobCrawlService service = new JobCrawlService(
                new JobCrawlRepository(mock(SqlStore.class)),
                mock(CurrentUser.class),
                mock(JobService.class),
                transactions()
            );
            assertThrows(IOException.class, () -> service.fetch("ITVIEC", URL));
            verify(response, never()).body();
            verify(connection).timeout(5000);
        }
    }

    @Test
    void mixedFetchOutcomesPersistActualCountersAndItems() throws Exception {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID(), job = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenReturn(Map.of("id", job));
        PlatformTransactionManager manager = transactions();
        String html = fixture();
        JobCrawlService service = new JobCrawlService(new JobCrawlRepository(db), user, jobs, manager) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                return new HttpPage(url, 200, url.endsWith("unsupported") ? "<h1>Challenge</h1>" : html);
            }
        };
        service.crawl(
            new CrawlRequest("ITVIEC", "Java", "Backend", "Ho Chi Minh", List.of(URL, "https://itviec.com/unsupported"))
        );
        verify(db).update(
            contains("UPDATE market_crawl_runs"),
            eq("PARTIAL"),
            eq(2),
            eq(1),
            eq(1),
            eq(0),
            eq(2),
            eq(0),
            eq(1),
            anyString(),
            any(),
            eq(run)
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> items = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).insert(eq("market_crawl_items"), items.capture());
        assertEquals(
            List.of("IMPORTED", "REJECTED"),
            items.getAllValues().stream().map(item -> item.get("item_status")).toList()
        );
        verify(user).requireStaffOrOperationsRole();
        verify(jobs, times(1)).importJob(any());
    }

    @Test
    void failedImportRollsBackAndRecordsFailureWithoutCountingAnImport() throws Exception {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenThrow(new IllegalStateException("simulated database failure"));
        PlatformTransactionManager manager = transactions();
        String html = fixture();
        new JobCrawlService(new JobCrawlRepository(db), user, jobs, manager) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                return new HttpPage(url, 200, html);
            }
        }.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(URL)));
        verify(manager).rollback(any());
        verify(db).update(
            contains("UPDATE market_crawl_runs"),
            eq("FAILED"),
            eq(1),
            eq(1),
            eq(0),
            eq(0),
            eq(1),
            eq(0),
            eq(1),
            anyString(),
            any(),
            eq(run)
        );
        verify(db).insert(eq("market_crawl_items"), argThat(item -> "IMPORT_FAILED".equals(item.get("item_status"))));
    }

    @Test
    void expiredPostingInvalidatesOldEmbeddingsAndSkipsImport() throws Exception {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        String html = fixture().replace("2099-12-31", "2000-01-01");
        new JobCrawlService(new JobCrawlRepository(db), user, jobs, transactions()) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                return new HttpPage(url, 200, html);
            }
        }.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(URL)));
        verify(jobs, never()).importJob(any());
        verify(db).update(startsWith("UPDATE market_job_embeddings"), eq("ITVIEC"), eq(URL));
        verify(db).update(contains("UPDATE market_job_posts j SET status='EXPIRED'"), eq("ITVIEC"), eq(URL));
        verify(db).insert(eq("market_crawl_items"), argThat(item -> "EXPIRED".equals(item.get("item_status"))));
    }

    @Test
    void changedJobReplacesPreviousUrlAndInvalidatesEmbeddings() {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        UUID job = UUID.randomUUID();
        when(db.insert(eq("market_job_posts"), anyMap())).thenReturn(job);
        when(db.one(anyString(), eq(job)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", job, "status", "ACTIVE")));
        var input = new ImportJobRequest(
            "ITviec",
            URL,
            "Java",
            "Example",
            "Hồ Chí Minh",
            null,
            "Backend",
            "Build reliable Java backend services and maintain production systems.",
            List.of()
        );
        new JobService(new JobRepository(db), user, mock(AiService.class)).importJob(input);
        verify(db).insert(eq("market_job_posts"), argThat(fields -> "ITVIEC".equals(fields.get("source_site"))));
        verify(db).update(contains("UPDATE market_job_embeddings SET is_current=false"), eq(URL), eq(job));
        verify(db).update(contains("status='REMOVED'"), eq(URL), eq(job));
    }

    @Test
    void discoveredDetailIsImportedEvenWhenNextSearchPageFailsAndCountersExposeFailure() throws Exception {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID(), job = UUID.randomUUID();
        String detailUrl = "https://itviec.com/it-jobs/java-fixture-1234";
        String pageOne = "https://itviec.com/it-jobs?query=Java&page=1";
        String pageTwo = "https://itviec.com/it-jobs?query=Java&page=2";
        List<String> fetched = new ArrayList<>();
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        when(jobs.importJob(any())).thenReturn(Map.of("id", job));
        String detailHtml = fixture();
        JobCrawlService service = new JobCrawlService(new JobCrawlRepository(db), user, jobs, transactions()) {

            @Override
            protected HttpPage fetch(
                String source,
                String url)
                throws IOException {
                fetched.add(url);
                if (url.equals(pageOne))
                    return new HttpPage(url, 200, """
                        <a href="/it-jobs/java-fixture-1234?utm_source=search">Detail</a>
                        <a href="/it-jobs/java">Listing</a>
                        <a href="https://attacker.test/it-jobs/java-fixture-5678">Foreign</a>
                        """);
                if (url.equals(pageTwo))
                    throw new IOException("Search temporarily unavailable");
                if (url.equals(detailUrl))
                    return new HttpPage(url, 200, detailHtml);
                throw new AssertionError("Unexpected fetch " + url);
            }
        };
        service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, null, 2, 3));

        assertEquals(List.of(pageOne, pageTwo, detailUrl), fetched);
        verify(jobs).importJob(argThat(input -> detailUrl.equals(input.sourceUrl()) && !input.skills().isEmpty()));
        verify(db).update(
            contains("UPDATE market_crawl_runs SET status="),
            eq("PARTIAL"),
            eq(1),
            eq(1),
            eq(1),
            eq(0),
            eq(1),
            eq(0),
            eq(1),
            anyString(),
            any(),
            eq(run)
        );
        verify(db).update(contains("search_pages_checked="), eq(1), eq(1), eq(3), eq(run));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> items = ArgumentCaptor.forClass(Map.class);
        verify(db, times(3)).insert(eq("market_crawl_items"), items.capture());
        assertEquals(
            List.of("SEARCH_FETCHED", "SEARCH_FETCH_FAILED", "IMPORTED"),
            items.getAllValues().stream().map(item -> item.get("item_status")).toList()
        );
    }

    @Test
    void missingAndGoneSourcesExpireWithoutCallingImportOrParsingTheirBodies() {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        JobService jobs = mock(JobService.class);
        UUID run = UUID.randomUUID();
        String missing = "https://itviec.com/it-jobs/missing-source-1234";
        String gone = "https://itviec.com/it-jobs/gone-source-5678";
        when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
        when(db.one(contains("market_crawl_runs"), eq(run)))
            .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
        new JobCrawlService(new JobCrawlRepository(db), user, jobs, transactions()) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                return new HttpPage(url, url.equals(missing) ? 404 : 410, "Not a job or JSON-LD document");
            }
        }.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(missing, gone)));
        verify(jobs, never()).importJob(any());
        verifyNoMoreInteractions(jobs);
        verify(db).update(startsWith("UPDATE market_job_sources"), eq("ITVIEC"), eq(missing));
        verify(db).update(startsWith("UPDATE market_job_sources"), eq("ITVIEC"), eq(gone));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> items = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).insert(eq("market_crawl_items"), items.capture());
        assertEquals(List.of(404, 410), items.getAllValues().stream().map(item -> item.get("http_status")).toList());
        assertTrue(items.getAllValues().stream().allMatch(item -> "EXPIRED".equals(item.get("item_status"))));
        verify(db).update(
            contains("UPDATE market_crawl_runs SET status="),
            eq("FAILED"),
            eq(2),
            eq(0),
            eq(0),
            eq(0),
            eq(2),
            eq(0),
            eq(2),
            anyString(),
            any(),
            eq(run)
        );
    }

    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager result = mock(PlatformTransactionManager.class);
        when(result.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        return result;
    }
}
