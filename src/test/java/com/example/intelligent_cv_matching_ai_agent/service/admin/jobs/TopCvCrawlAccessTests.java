package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TopCvCrawlAccessTests {

    private static final String SEARCH = "https://www.topcv.vn/tim-viec-lam?keyword=Java&page=1";

    private static final String DETAIL = "https://www.topcv.vn/viec-lam/java-developer/2267342.html";

    private static final String CHALLENGE = "<html><head><title>Just a moment...</title></head>"
        + "<body><script src='/cdn-cgi/challenge-platform/start.js'></script></body></html>";

    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void challengeResponseRetainsItsStatusAndStopsDiscoveryWithoutImportingOrRetrying(
        int status)
        throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.response.statusCode()).thenReturn(status);
        when(fixture.response.header("cf-mitigated")).thenReturn("challenge");
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(SEARCH)).thenReturn(fixture.connection);
            fixture.service.crawl(new CrawlRequest("TOPCV", "Java", "Backend", null, null, 20, 3));

            fixture.assertSearchFailure(status, "Cloudflare verification");
            verify(fixture.connection).execute();
            jsoup.verify(() -> Jsoup.connect(SEARCH));
            jsoup.verifyNoMoreInteractions();
            verifyNoInteractions(fixture.jobs);
        }
    }

    @Test
    void genericForbiddenSearchAlsoKeeps403InsteadOfSavingNullStatus() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.response.statusCode()).thenReturn(403);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(SEARCH)).thenReturn(fixture.connection);
            fixture.service.crawl(new CrawlRequest("TOPCV", "Java", "Backend", null, null, 20, 3));
            fixture.assertSearchFailure(403, "Search returned HTTP 403");
        }
    }

    @Test
    void challengeServedWith200IsAFailedCrawlRatherThanAnEmptySuccessfulSearch() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.response.statusCode()).thenReturn(200);
        when(fixture.response.body()).thenReturn(CHALLENGE);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(SEARCH)).thenReturn(fixture.connection);
            jsoup.when(() -> Jsoup.parse(CHALLENGE)).thenReturn(org.jsoup.parser.Parser.parse(CHALLENGE, SEARCH));
            fixture.service.crawl(new CrawlRequest("TOPCV", "Java", "Backend", null, null, 20, 3));
            fixture.assertSearchFailure(200, "Cloudflare verification");
            verifyNoInteractions(fixture.jobs);
        }
    }

    @Test
    void rateLimitedSourceStopsAfterOneRequestAndPersists429() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.response.statusCode()).thenReturn(429);
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(SEARCH)).thenReturn(fixture.connection);
            fixture.service.crawl(new CrawlRequest("TOPCV", "Java", "Backend", null, null, 20, 3));
            fixture.assertSearchFailure(429, "rate limit");
            verify(fixture.connection).execute();
        }
    }

    @Test
    void blockedDetailDoesNotExpireAnExistingJobOrCallTheImporter() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.response.statusCode()).thenReturn(403);
        when(fixture.response.header("cf-mitigated")).thenReturn("challenge");
        try (var jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(DETAIL)).thenReturn(fixture.connection);
            fixture.service.crawl(new CrawlRequest("TOPCV", "Java", "Backend", null, List.of(DETAIL)));
            verify(fixture.db).insert(
                eq("market_crawl_items"),
                argThat(
                    item -> "FETCH_FAILED".equals(item.get("item_status"))
                        && Integer.valueOf(403).equals(item.get("http_status"))
                        && item.get("message").toString().contains("Cloudflare verification")
                )
            );
            verify(fixture.db, never()).update(startsWith("UPDATE market_job_sources"), any(), any());
            verifyNoInteractions(fixture.jobs);
        }
    }

    @Test
    void usingCloudflareAsACdnDoesNotRejectOrdinaryJobHtml() throws Exception {
        Fixture fixture = new Fixture();
        String html = "<html><head><title>Java Developer</title></head>"
            + "<body><h1>Develop services with Cloudflare</h1></body></html>";
        when(fixture.response.statusCode()).thenReturn(200);
        when(fixture.response.header("server")).thenReturn("cloudflare");
        when(fixture.response.body()).thenReturn(html);
        try (var jsoup = mockStatic(Jsoup.class, CALLS_REAL_METHODS)) {
            jsoup.when(() -> Jsoup.connect(DETAIL)).thenReturn(fixture.connection);
            assertEquals(html, fixture.service.fetch("TOPCV", DETAIL).html());
        }
    }

    private static final class Fixture {

        final SqlStore db = mock(SqlStore.class);

        final JobService jobs = mock(JobService.class);

        final Connection connection = mock(Connection.class, RETURNS_SELF);

        final Connection.Response response = mock(Connection.Response.class);

        final UUID run = UUID.randomUUID();

        final JobCrawlService service;

        Fixture() throws Exception {
            when(db.insert(eq("market_crawl_runs"), anyMap())).thenReturn(run);
            when(db.one(contains("market_crawl_runs"), eq(run)))
                .thenAnswer(ignored -> new LinkedHashMap<>(Map.of("id", run)));
            when(connection.execute()).thenReturn(response);
            when(response.bodyAsBytes()).thenReturn(CHALLENGE.getBytes(StandardCharsets.UTF_8));
            when(response.contentType()).thenReturn("text/html; charset=UTF-8");
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
            service = new JobCrawlService(new JobCrawlRepository(db), mock(CurrentUser.class), jobs, manager);
        }

        void assertSearchFailure(
            int status,
            String message) {
            verify(db).insert(
                eq("market_crawl_items"),
                argThat(
                    item -> "SEARCH_FETCH_FAILED".equals(item.get("item_status"))
                        && Integer.valueOf(status).equals(item.get("http_status"))
                        && item.get("message").toString().contains(message)
                )
            );
            verify(db).update(
                contains("UPDATE market_crawl_runs SET status="),
                eq("FAILED"),
                eq(0),
                eq(0),
                eq(0),
                eq(0),
                eq(0),
                eq(0),
                eq(1),
                contains(message),
                any(),
                eq(run)
            );
            verify(db).update(contains("search_pages_checked="), eq(1), eq(0), eq(0), eq(run));
        }
    }
}
