package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobCrawlRateLimitTests {

    private static final String FIRST = "https://itviec.com/it-jobs/java-developer-first-1001";

    private static final String BLOCKED = "https://itviec.com/it-jobs/java-developer-blocked-1002";

    private static final String LATER = "https://itviec.com/it-jobs/java-developer-later-1003";

    private static final String LAST = "https://itviec.com/it-jobs/java-developer-last-1004";

    private static final String POSTING = """
        <script type="application/ld+json">
        {"@type":"JobPosting","title":"Java Developer",
         "description":"Build reliable Java applications and maintain production backend services.",
         "hiringOrganization":{"name":"Example Company"},"validThrough":"2099-12-31"}
        </script>
        """;

    @Test
    void rateLimitKeepsSuccessfulImportAndDefersRemainingUrlsWithoutFetchingThem() throws Exception {
        Fixture fixture = new Fixture();
        fixture.page(FIRST, 200, POSTING);
        Connection.Response blocked = fixture.page(BLOCKED, 429, "Too many requests");
        when(blocked.header("Retry-After")).thenReturn("120");

        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service
                .crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(FIRST, BLOCKED, LATER, LAST)));
        }

        assertEquals(List.of(FIRST, BLOCKED), fixture.requests);
        assertEquals(List.of("IMPORTED", "SOURCE_BLOCKED", "DEFERRED", "DEFERRED"), fixture.statuses());
        assertEquals(429, fixture.items.get(1).get("http_status"));
        assertDeferred(fixture.items.get(2), LATER);
        assertDeferred(fixture.items.get(3), LAST);
        verify(fixture.jobs).importJob(any());
        verify(fixture.access).blocked("ITVIEC", "120", false);
        verify(fixture.access, times(2)).beforeRequest("ITVIEC");
        fixture.assertRun("PARTIAL", 2, 1, 1, 2, 1, "rate limit");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 403, 429})
    void challengeStopsTheBatchWithoutExpiringJobsOrImportingChallengeHtml(
        int status)
        throws Exception {
        Fixture fixture = new Fixture();
        Connection.Response blocked = fixture.page(BLOCKED, status, "Cloudflare verification");
        when(blocked.header("cf-mitigated")).thenReturn("challenge");

        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(BLOCKED, LATER)));
        }

        assertEquals(List.of(BLOCKED), fixture.requests);
        assertEquals(List.of("SOURCE_BLOCKED", "DEFERRED"), fixture.statuses());
        assertEquals(status, fixture.items.getFirst().get("http_status"));
        assertNull(fixture.items.getFirst().get("job_title"));
        assertDeferred(fixture.items.get(1), LATER);
        verify(fixture.access).blocked("ITVIEC", null, true);
        verify(fixture.jobs, never()).importJob(any());
        verifyNoMoreInteractions(fixture.jobs);
        verify(fixture.db, never()).update(startsWith("UPDATE market_job_sources"), any(), any());
        fixture.assertRun("FAILED", 1, 0, 0, 1, 1, "Cloudflare");
    }

    @Test
    void challengeHtmlWith200AlsoStopsDetailsWhenTheChallengeHeaderIsMissing() throws Exception {
        Fixture fixture = new Fixture();
        fixture.page(
            BLOCKED,
            200,
            "<html><head><title>Just a moment...</title></head>"
                + "<body><script src='/cdn-cgi/challenge-platform/start.js'></script></body></html>"
        );

        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(BLOCKED, LATER)));
        }

        assertEquals(List.of(BLOCKED), fixture.requests);
        assertEquals(List.of("SOURCE_BLOCKED", "DEFERRED"), fixture.statuses());
        assertEquals(200, fixture.items.getFirst().get("http_status"));
        verify(fixture.jobs, never()).importJob(any());
        verifyNoMoreInteractions(fixture.jobs);
        verify(fixture.access).blocked("ITVIEC", null, true);
    }

    @Test
    void blockedLaterSearchPageDefersAlreadyDiscoveredDetailsWithoutRequestingThem() throws Exception {
        Fixture fixture = new Fixture();
        String searchOne = "https://itviec.com/it-jobs?query=Java&page=1";
        String searchTwo = "https://itviec.com/it-jobs?query=Java&page=2";
        fixture.page(searchOne, 200, "<a href='" + FIRST + "'>First</a><a href='" + LATER + "'>Later</a>");
        fixture.page(searchTwo, 429, "Too many requests");

        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, null, 3, 2));
        }

        assertEquals(List.of(searchOne, searchTwo), fixture.requests);
        assertEquals(List.of("SEARCH_FETCHED", "SEARCH_BLOCKED", "DEFERRED", "DEFERRED"), fixture.statuses());
        assertEquals(429, fixture.items.get(1).get("http_status"));
        assertDeferred(fixture.items.get(2), FIRST);
        assertDeferred(fixture.items.get(3), LATER);
        verify(fixture.jobs, never()).importJob(any());
        verifyNoMoreInteractions(fixture.jobs);
        verify(fixture.db).update(contains("search_pages_checked="), eq(2), eq(2), eq(0), eq(fixture.run));
        fixture.assertRun("FAILED", 0, 0, 0, 0, 1, "rate limit");
    }

    @Test
    void ordinaryServerFailureDoesNotDeferUnrelatedFollowingDetail() throws Exception {
        Fixture fixture = new Fixture();
        fixture.page(BLOCKED, 500, "Temporary server error");
        fixture.page(FIRST, 200, POSTING);

        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(BLOCKED, FIRST)));
        }

        assertEquals(List.of(BLOCKED, FIRST), fixture.requests);
        assertEquals(List.of("REJECTED", "IMPORTED"), fixture.statuses());
        verify(fixture.jobs).importJob(any());
        verify(fixture.access, never()).blocked(anyString(), nullable(String.class), anyBoolean());
        fixture.assertRun("PARTIAL", 2, 1, 1, 2, 1, "rejected 1");
    }

    private static void assertDeferred(
        Map<String, Object> item,
        String url) {
        assertEquals("DEFERRED", item.get("item_status"));
        assertEquals(url, item.get("source_url"));
        assertNull(item.get("http_status"));
        assertNull(item.get("job_title"));
        assertFalse(item.get("message").toString().isBlank());
    }

    @Test
    void rateLimitStillStopsTheSourceWhenItsErrorBodyIsOversized() throws Exception {
        Fixture fixture = new Fixture();
        Connection.Response blocked = fixture.page(BLOCKED, 429, "Too many requests");
        when(blocked.bodyAsBytes()).thenReturn(new byte[2 * 1024 * 1024 + 1]);
        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(BLOCKED, LATER)));
        }
        assertEquals(List.of(BLOCKED), fixture.requests);
        assertEquals(List.of("SOURCE_BLOCKED", "DEFERRED"), fixture.statuses());
        verify(fixture.access).blocked("ITVIEC", null, false);
    }

    @Test
    void interruptedRedirectCountsTheAttemptAlreadySentAndDefersTheRest() throws Exception {
        Fixture fixture = new Fixture();
        Connection.Response redirect = fixture.page(FIRST, 302, "Redirecting");
        when(redirect.header("Location")).thenReturn(BLOCKED);
        doNothing().doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Pacing interrupted");
        }).when(fixture.access).beforeRequest("ITVIEC");
        try (var jsoup = fixture.mockSourceRequests()) {
            fixture.service.crawl(new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(FIRST, LATER)));
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(FIRST), fixture.requests);
        assertEquals(List.of("FETCH_FAILED", "DEFERRED"), fixture.statuses());
        assertEquals(302, fixture.items.getFirst().get("http_status"));
        fixture.assertRun("FAILED", 1, 0, 0, 1, 1, "interrupted");
    }

    @Test
    void blockedSourcePreventsBothManualAndScheduledRequestsWithoutCreatingEmptyRuns() throws Exception {
        Fixture fixture = new Fixture();
        fixture.page(BLOCKED, 429, "Too many requests");
        JobCrawlSourceAccess access =
            new JobCrawlSourceAccess(Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(15));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        JobCrawlService service = new JobCrawlService(
            new JobCrawlRepository(fixture.db),
            mock(CurrentUser.class),
            fixture.jobs,
            manager,
            access
        );
        UUID admin = UUID.randomUUID();
        when(fixture.db.optional(startsWith("SELECT id FROM users"), eq(admin)))
            .thenReturn(Optional.of(Map.of("id", admin)));
        CrawlRequest request = new CrawlRequest("ITVIEC", "Java", "Backend", null, List.of(BLOCKED, LATER));
        try (var jsoup = fixture.mockSourceRequests()) {
            service.crawl(request);
            assertEquals(429, assertThrows(ApiException.class, () -> service.crawl(request)).status());
            assertEquals(429, assertThrows(ApiException.class, () -> service.crawlAs(request, admin)).status());
        }
        assertEquals(List.of(BLOCKED), fixture.requests);
        verify(fixture.db, times(1)).insert(eq("market_crawl_runs"), anyMap());
        verify(fixture.jobs, never()).importJob(any());
        verifyNoMoreInteractions(fixture.jobs);
    }

    private static final class Fixture {

        final SqlStore db = mock(SqlStore.class);

        final JobService jobs = mock(JobService.class);

        final JobCrawlSourceAccess access = mock(JobCrawlSourceAccess.class);

        final UUID run = UUID.randomUUID();

        final List<String> requests = new ArrayList<>();

        final List<Map<String, Object>> items = new ArrayList<>();

        final Map<String, Connection> connections = new LinkedHashMap<>();

        final JobCrawlService service;

        Fixture() {
            when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
            when(db.one(contains("market_crawl_runs"), eq(run)))
                .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
            when(db.insert(eq("market_crawl_items"), anyMap())).thenAnswer(call -> {
                items.add(new LinkedHashMap<>(call.getArgument(1)));
                return UUID.randomUUID();
            });
            when(jobs.importJob(any())).thenReturn(Map.of("id", UUID.randomUUID()));
            when(access.blocked(anyString(), nullable(String.class), anyBoolean()))
                .thenReturn(Instant.parse("2030-01-01T00:15:00Z"));
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
            service = new JobCrawlService(new JobCrawlRepository(db), mock(CurrentUser.class), jobs, manager, access);
        }

        Connection.Response page(
            String url,
            int status,
            String html)
            throws Exception {
            Connection connection = mock(Connection.class, RETURNS_SELF);
            Connection.Response response = mock(Connection.Response.class);
            when(connection.execute()).thenReturn(response);
            when(response.statusCode()).thenReturn(status);
            when(response.bodyAsBytes()).thenReturn(html.getBytes(StandardCharsets.UTF_8));
            when(response.contentType()).thenReturn("text/html; charset=UTF-8");
            when(response.body()).thenReturn(html);
            connections.put(url, connection);
            return response;
        }

        MockedStatic<Jsoup> mockSourceRequests() {
            MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class, CALLS_REAL_METHODS);
            jsoup.when(() -> Jsoup.connect(anyString())).thenAnswer(call -> {
                String url = call.getArgument(0);
                Connection connection = connections.get(url);
                if (connection == null) {
                    throw new AssertionError("Unexpected source request: " + url);
                }
                requests.add(url);
                return connection;
            });
            return jsoup;
        }

        List<Object> statuses() {
            return items.stream().map(item -> item.get("item_status")).toList();
        }

        void assertRun(
            String outcome,
            int fetched,
            int parsed,
            int imported,
            int checked,
            int rejected,
            String summary) {
            verify(db).update(
                contains("UPDATE market_crawl_runs SET status="),
                eq(outcome),
                eq(fetched),
                eq(parsed),
                eq(imported),
                eq(0),
                eq(checked),
                eq(0),
                eq(rejected),
                contains(summary),
                any(),
                eq(run)
            );
        }
    }
}
