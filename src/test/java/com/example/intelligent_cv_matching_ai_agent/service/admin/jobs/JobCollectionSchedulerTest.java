package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCollectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobCollectionSchedulerTest {

    private JobCollectionProperties configuration(
        UUID actor) {
        JobCollectionProperties properties = new JobCollectionProperties();
        properties.setEnabled(true);
        properties.setActorId(actor);
        properties.setSources(List.of(source("ITVIEC"), source("TOPCV")));
        return properties;
    }

    private JobCollectionProperties.Source source(
        String site) {
        JobCollectionProperties.Source source = new JobCollectionProperties.Source();
        source.setSourceSite(site);
        source.setKeyword("Java");
        source.setRoleCategory("Backend");
        source.setLocation("Ho Chi Minh");
        source.setLimit(7);
        source.setMaxPages(2);
        return source;
    }

    private JobCollectionScheduler scheduler(
        JobCollectionProperties configuration,
        JobCollectionRepository db,
        JobCrawlService crawler,
        boolean expirationEnabled) {
        CrawlSettingsService settings = mock(CrawlSettingsService.class);
        when(settings.effective()).thenReturn(
            new CrawlSettingsResponse(
                configuration.isEnabled(),
                configuration.getFixedDelay().toString(),
                configuration.getActorId(),
                configuration.getSources().stream().map(this::sourceResponse).toList(),
                "ENVIRONMENT",
                0,
                null,
                null
            )
        );
        return new JobCollectionScheduler(settings, db, crawler, expirationEnabled, Duration.ofMinutes(15), 500, true);
    }

    @Test
    void disabledCollectionAndExpirationDoNotAccessDatabaseOrNetwork() {
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        JobCollectionScheduler scheduler = scheduler(new JobCollectionProperties(), db, crawler, false);
        scheduler.collect();
        scheduler.expire();
        verifyNoInteractions(db, crawler);
    }

    @Test
    void inactiveOrNonAdministratorActorCannotStartCollection() {
        UUID actor = UUID.randomUUID();
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        scheduler(configuration(actor), db, crawler, false).collect();
        verify(db).isActiveAdministrator(actor);
        verify(db, never()).acquireCollectionLease(anyString(), any(), any(), any());
        verifyNoInteractions(crawler);
    }

    @Test
    void anotherReplicaHoldingLeasePreventsExternalCrawl() {
        UUID actor = UUID.randomUUID();
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        scheduler(configuration(actor), db, crawler, false).collect();
        verify(db, times(2))
            .acquireCollectionLease(anyString(), any(), eq(Duration.ofMinutes(15)), eq(Duration.ofHours(6)));
        verifyNoInteractions(crawler);
        verify(db, never()).completeLease(anyString(), any(), any());
    }

    @Test
    void sourceFailureDoesNotSkipNextSourceAndSuccessfulLeaseIsCompleted() {
        UUID actor = UUID.randomUUID();
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.acquireCollectionLease(anyString(), any(), any(), any())).thenReturn(true);
        when(crawler.crawlAs(any(), eq(actor))).thenThrow(new IllegalStateException("Fixture rejection"))
            .thenReturn(Map.of("id", UUID.randomUUID(), "status", "COMPLETED"));

        scheduler(configuration(actor), db, crawler, false).collect();

        ArgumentCaptor<CrawlRequest> requests = ArgumentCaptor.forClass(CrawlRequest.class);
        verify(crawler, times(2)).crawlAs(requests.capture(), eq(actor));
        assertEquals(
            List.of("ITVIEC", "TOPCV"),
            requests.getAllValues().stream().map(CrawlRequest::sourceSite).toList()
        );
        assertTrue(requests.getValue().urls().isEmpty());
        assertEquals(7, requests.getValue().effectiveLimit());
        assertEquals(2, requests.getValue().effectiveMaxPages());
        verify(db).completeLease(anyString(), any(), eq("IllegalStateException: Fixture rejection"));
        verify(db).completeLease(anyString(), any(), isNull());
    }

    @Test
    void failedCrawlResponseIsRetainedInLeaseMonitoring() {
        UUID actor = UUID.randomUUID(), run = UUID.randomUUID();
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        JobCollectionProperties properties = configuration(actor);
        properties.setSources(List.of(source("ITVIEC")));
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.acquireCollectionLease(anyString(), any(), any(), any())).thenReturn(true);
        when(crawler.crawlAs(any(), eq(actor))).thenReturn(Map.of("id", run, "status", "PARTIAL"));

        scheduler(properties, db, crawler, false).collect();

        verify(db).completeLease(anyString(), any(), eq("Crawl " + run + " ended with status PARTIAL"));
    }

    @Test
    void expirationLeasePreventsReplicasFromRunningMaintenanceTwice() {
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        JobCollectionScheduler scheduler = scheduler(new JobCollectionProperties(), db, crawler, true);
        when(db.acquireLease(eq("job-expiration"), any(), any(), any())).thenReturn(false, true);
        when(db.expireSources(500)).thenReturn(new JobCollectionRepository.ExpirationResult(2, 1, 1));

        scheduler.expire();
        scheduler.expire();

        verify(db, times(1)).expireSources(500);
        verify(db, times(1)).completeLease(eq("job-expiration"), any(), isNull());
        verifyNoInteractions(crawler);
    }

    @Test
    void equivalentSearchConfigurationUsesSameDurableLeaseIdentity() {
        JobCollectionProperties.Source first = source("ITVIEC"), second = source("ITVIEC");
        second.setKeyword(" java ");
        second.setRoleCategory(" backend ");
        second.setLocation(" ho chi minh ");
        assertEquals(
            JobCollectionScheduler.leaseName(sourceResponse(first)),
            JobCollectionScheduler.leaseName(sourceResponse(second))
        );
        second.setSourceSite("TOPCV");
        assertNotEquals(
            JobCollectionScheduler.leaseName(sourceResponse(first)),
            JobCollectionScheduler.leaseName(sourceResponse(second))
        );
        assertTrue(JobCollectionScheduler.leaseName(sourceResponse(first)).length() <= 100);
    }

    @Test
    void persistedEnableAndDisableTakeEffectAtTheNextPollWithoutRestarting() {
        UUID actor = UUID.randomUUID();
        CrawlSettingsService settings = mock(CrawlSettingsService.class);
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        CrawlSourceSettingsResponse source = selectedSource("ITVIEC", "Ha Noi");
        when(settings.effective()).thenReturn(
            persisted(false, actor, "PT6H", List.of(source)),
            persisted(true, actor, "PT6H", List.of(source)),
            persisted(false, actor, "PT6H", List.of(source))
        );
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.acquireCollectionLease(anyString(), any(), any(), any())).thenReturn(true);
        when(crawler.crawlAs(any(), eq(actor))).thenReturn(Map.of("status", "COMPLETED"));
        JobCollectionScheduler scheduler =
            new JobCollectionScheduler(settings, db, crawler, false, Duration.ofMinutes(15), 500, true);

        scheduler.collect();
        scheduler.collect();
        scheduler.collect();

        verify(settings, times(3)).effective();
        verify(db).isActiveAdministrator(actor);
        verify(crawler).crawlAs(argThat(request -> "Ha Noi".equals(request.location())), eq(actor));
        verify(db).acquireCollectionLease(anyString(), any(), any(), eq(Duration.ofHours(6)));
    }

    @Test
    void settingsChangesStopRemainingOldSourcesAndTheNextPollUsesTheNewSnapshot() {
        UUID actor = UUID.randomUUID();
        CrawlSettingsService settings = mock(CrawlSettingsService.class);
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        CrawlSettingsResponse revised = new CrawlSettingsResponse(
            true,
            "PT1M",
            actor,
            List.of(selectedSource("TOPCV", "Da Nang")),
            "DATABASE",
            2,
            actor,
            null
        );
        AtomicReference<CrawlSettingsResponse> stored = new AtomicReference<>(
            persisted(
                true,
                actor,
                "PT6H",
                List.of(selectedSource("ITVIEC", "Ha Noi"), selectedSource("TOPCV", "Remote"))
            )
        );
        AtomicInteger calls = new AtomicInteger();
        when(settings.effective()).thenAnswer(ignored -> stored.get());
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.acquireCollectionLease(anyString(), any(), any(), any())).thenReturn(true);
        when(crawler.crawlAs(any(), eq(actor))).thenAnswer(ignored -> {
            if (calls.getAndIncrement() == 0) {
                stored.set(revised);
            }
            return Map.of("status", "COMPLETED");
        });
        JobCollectionScheduler scheduler =
            new JobCollectionScheduler(settings, db, crawler, false, Duration.ofMinutes(15), 500, true);

        scheduler.collect();
        scheduler.collect();

        ArgumentCaptor<CrawlRequest> requests = ArgumentCaptor.forClass(CrawlRequest.class);
        verify(crawler, times(2)).crawlAs(requests.capture(), eq(actor));
        assertEquals(
            List.of("Ha Noi", "Da Nang"),
            requests.getAllValues().stream().map(CrawlRequest::location).toList()
        );
        verify(settings, times(3)).effective();
        verify(db).acquireCollectionLease(anyString(), any(), any(), eq(Duration.ofHours(6)));
        verify(db).acquireCollectionLease(anyString(), any(), any(), eq(Duration.ofMinutes(1)));
    }

    @Test
    void pausingBetweenSourcesFinishesTheCurrentSourceAndSkipsTheRemainingWork() {
        UUID actor = UUID.randomUUID();
        CrawlSettingsService settings = mock(CrawlSettingsService.class);
        JobCollectionRepository db = mock(JobCollectionRepository.class);
        JobCrawlService crawler = mock(JobCrawlService.class);
        CrawlSettingsResponse paused =
            new CrawlSettingsResponse(false, "PT6H", actor, List.of(), "DATABASE", 2, actor, null);
        AtomicReference<CrawlSettingsResponse> stored = new AtomicReference<>(
            persisted(
                true,
                actor,
                "PT6H",
                List.of(selectedSource("ITVIEC", "Ha Noi"), selectedSource("TOPCV", "Remote"))
            )
        );
        when(settings.effective()).thenAnswer(ignored -> stored.get());
        when(db.isActiveAdministrator(actor)).thenReturn(true);
        when(db.acquireCollectionLease(anyString(), any(), any(), any())).thenReturn(true);
        when(crawler.crawlAs(any(), eq(actor))).thenAnswer(ignored -> {
            stored.set(paused);
            return Map.of("status", "COMPLETED");
        });
        JobCollectionScheduler scheduler =
            new JobCollectionScheduler(settings, db, crawler, false, Duration.ofMinutes(15), 500, true);

        scheduler.collect();
        scheduler.collect();

        verify(crawler).crawlAs(argThat(request -> "Ha Noi".equals(request.location())), eq(actor));
        verify(db).completeLease(anyString(), any(), isNull());
        verify(settings, times(3)).effective();
    }

    @Test
    void locationAliasesShareALeaseButDistinctSelectedCitiesHaveIndependentLeases() {
        CrawlSourceSettingsResponse hanoi = selectedSource("ITVIEC", "Hanoi");
        CrawlSourceSettingsResponse accented = selectedSource("ITVIEC", "Hà Nội");
        CrawlSourceSettingsResponse daNang = selectedSource("ITVIEC", "Da Nang");

        assertEquals(JobCollectionScheduler.leaseName(hanoi), JobCollectionScheduler.leaseName(accented));
        assertNotEquals(JobCollectionScheduler.leaseName(hanoi), JobCollectionScheduler.leaseName(daNang));
    }

    private CrawlSourceSettingsResponse sourceResponse(
        JobCollectionProperties.Source source) {
        return new CrawlSourceSettingsResponse(
            source.getSourceSite(),
            source.getKeyword(),
            source.getRoleCategory(),
            source.getLocation(),
            source.getLimit(),
            source.getMaxPages()
        );
    }

    private CrawlSourceSettingsResponse selectedSource(
        String site,
        String location) {
        return new CrawlSourceSettingsResponse(site, "Java", "Backend", location, 7, 2);
    }

    private CrawlSettingsResponse persisted(
        boolean enabled,
        UUID actor,
        String delay,
        List<CrawlSourceSettingsResponse> sources) {
        return new CrawlSettingsResponse(enabled, delay, actor, sources, "DATABASE", 1, actor, null);
    }

    @Test
    void disabledSchedulerDoesNotReadDatabaseSettingsOrStartCrawling() {
        var settings = mock(CrawlSettingsService.class);
        var db = mock(JobCollectionRepository.class);
        var crawler = mock(JobCrawlService.class);
        var scheduler = new JobCollectionScheduler(settings, db, crawler, true, Duration.ofMinutes(15), 500, false);
        scheduler.collect();
        verifyNoInteractions(settings, db, crawler);
    }
}
