package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSourceSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.CrawlSettingsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCollectionRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlSettingsServiceTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-10-01T00:00:00Z");

    private final CrawlSettingsRepository db = mock(CrawlSettingsRepository.class);

    private final JobCollectionRepository administrators = mock(JobCollectionRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final JobCollectionProperties environment = new JobCollectionProperties();

    private final CrawlSettingsService service = new CrawlSettingsService(db, environment, administrators, current);

    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void bootstrap() {
        when(current.id()).thenReturn(actor);
        when(administrators.isActiveAdministrator(actor)).thenReturn(true);
        environment.setEnabled(true);
        environment.setActorId(actor);
        JobCollectionProperties.Source source = new JobCollectionProperties.Source();
        source.setSourceSite("ITVIEC");
        source.setKeyword("Java");
        source.setRoleCategory("Backend");
        source.setLocation("Ho Chi Minh");
        environment.setSources(List.of(source));
    }

    @Test
    void absentSavedSettingsUseTheEnvironmentWithoutPersistingDefaults() {
        when(db.find()).thenReturn(Optional.empty());

        CrawlSettingsResponse result = service.get();

        assertEquals("ENVIRONMENT", result.configurationSource());
        assertTrue(result.enabled());
        assertEquals(actor, result.actorId());
        assertEquals("PT6H", result.fixedDelay());
        assertEquals(0, result.version());
        assertNull(result.updatedBy());
        assertNull(result.updatedAt());
        verify(current).requireStaffOrOperationsRole();
        verify(db, never()).save(anyBoolean(), any(), any(), anyList(), any());
        verify(db, never()).audit(any());
    }

    @Test
    void savedDisabledSettingsOverrideEnabledEnvironmentDefaults() {
        when(db.find()).thenReturn(Optional.of(row(false, Duration.ofMinutes(1), List.of(), 3)));

        CrawlSettingsResponse result = service.get();

        assertFalse(result.enabled());
        assertEquals("DATABASE", result.configurationSource());
        assertEquals(3, result.version());
        assertEquals(UPDATED_AT, result.updatedAt());
        assertEquals(actor, result.updatedBy());
        assertEquals(List.of(), result.sources());
    }

    @Test
    void effectiveSettingsReloadEachTimeWithoutRequiringAWebIdentity() {
        when(db.find()).thenReturn(
            Optional.of(row(false, Duration.ofMinutes(1), List.of(), 1)),
            Optional.of(row(true, Duration.ofHours(2), List.of(sourceRow("Ha Noi")), 2))
        );

        assertFalse(service.effective().enabled());
        CrawlSettingsResponse updated = service.effective();

        assertTrue(updated.enabled());
        assertEquals("PT2H", updated.fixedDelay());
        assertEquals("Ha Noi", updated.sources().getFirst().location());
        verify(db, times(2)).find();
        verifyNoInteractions(current);
    }

    @Test
    void databaseFailureDoesNotSilentlyRestoreEnvironmentDefaults() {
        when(db.find()).thenThrow(new ApiException(503, "Database unavailable"));

        assertThrows(ApiException.class, service::effective);

        verifyNoInteractions(current);
    }

    @Test
    void enablingNormalizesSourcesAndDefaultsActorToTheSavingAdministrator() {
        when(db.save(anyBoolean(), any(), any(), anyList(), any()))
            .thenReturn(row(true, Duration.ofMinutes(1), List.of(sourceRow("Ha Noi")), 1));
        CrawlSettingsRequest request = new CrawlSettingsRequest(
            true,
            "PT1M",
            null,
            List.of(new CrawlSourceSettingsRequest("ITVIEC", " Java ", " Backend ", "Hà Nội", 5, 2))
        );

        CrawlSettingsResponse result = service.update(request);

        assertEquals(actor, result.actorId());
        assertEquals("DATABASE", result.configurationSource());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CrawlSourceSettingsResponse>> sources = ArgumentCaptor.forClass(List.class);
        verify(db).save(eq(true), eq(Duration.ofMinutes(1)), eq(actor), sources.capture(), eq(actor));
        assertEquals("Java", sources.getValue().getFirst().keyword());
        assertEquals("Backend", sources.getValue().getFirst().roleCategory());
        assertEquals("Ha Noi", sources.getValue().getFirst().location());
        assertEquals(5, sources.getValue().getFirst().limit());
        assertEquals(2, sources.getValue().getFirst().maxPages());
        verify(db).audit(actor);
        verify(current).requireOperationsRole();
        verify(administrators).isActiveAdministrator(actor);
    }

    @Test
    void disablingCanPersistAnEmptySourceList() {
        when(db.save(anyBoolean(), any(), any(), anyList(), any()))
            .thenReturn(row(false, Duration.ofHours(6), List.of(), 1));

        assertFalse(service.update(new CrawlSettingsRequest(false, "PT6H", null, List.of())).enabled());

        verify(db).save(false, Duration.ofHours(6), actor, List.of(), actor);
        verify(db).audit(actor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT59S", "PT-1M", "P31D", "soon", " "})
    void invalidOrUnboundedIntervalsAreRejectedBeforePersistence(
        String interval) {
        assertThrows(ApiException.class, () -> service.update(request(interval, actor, List.of(source("Ha Noi")))));
        verify(db, never()).save(anyBoolean(), any(), any(), anyList(), any());
        verify(db, never()).audit(any());
    }

    @Test
    void largestAllowedIntervalCanBeSaved() {
        when(db.save(anyBoolean(), any(), any(), anyList(), any()))
            .thenReturn(row(true, Duration.ofDays(30), List.of(sourceRow("Ha Noi")), 1));

        assertEquals("PT720H", service.update(request("P30D", actor, List.of(source("Ha Noi")))).fixedDelay());

        verify(db).save(eq(true), eq(Duration.ofDays(30)), eq(actor), anyList(), eq(actor));
    }

    @Test
    void duplicateAliasEquivalentSearchesCannotBeSavedAsIndependentSources() {
        CrawlSettingsRequest request = request(
            "PT6H",
            actor,
            List.of(source("Hà Nội"), new CrawlSourceSettingsRequest("ITVIEC", " java ", " backend ", "Hanoi", 10, 3))
        );

        assertThrows(ApiException.class, () -> service.update(request));

        verify(db, never()).save(anyBoolean(), any(), any(), anyList(), any());
        verify(db, never()).audit(any());
    }

    @Test
    void enablingRequiresSourcesAndAnActiveAdministratorActor() {
        assertThrows(ApiException.class, () -> service.update(request("PT6H", actor, List.of())));
        UUID inactive = UUID.randomUUID();
        assertThrows(ApiException.class, () -> service.update(request("PT6H", inactive, List.of(source("Ha Noi")))));

        verify(db, never()).save(anyBoolean(), any(), any(), anyList(), any());
        verify(db, never()).audit(any());
    }

    @Test
    void unauthorizedCallersCannotReadOrChangeSettings() {
        doThrow(new ApiException(403, "Administrator required")).when(current).requireStaffOrOperationsRole();
        doThrow(new ApiException(403, "Administrator required")).when(current).requireOperationsRole();

        assertThrows(ApiException.class, service::get);
        assertThrows(ApiException.class, () -> service.update(request("PT6H", actor, List.of(source("Ha Noi")))));

        verifyNoInteractions(db, administrators);
    }

    private CrawlSettingsRequest request(
        String interval,
        UUID actorId,
        List<CrawlSourceSettingsRequest> sources) {
        return new CrawlSettingsRequest(true, interval, actorId, sources);
    }

    private CrawlSourceSettingsRequest source(
        String location) {
        return new CrawlSourceSettingsRequest("ITVIEC", "Java", "Backend", location, 5, 2);
    }

    private Map<String, Object> sourceRow(
        String location) {
        return Map.of(
            "sourceSite",
            "ITVIEC",
            "keyword",
            "Java",
            "roleCategory",
            "Backend",
            "location",
            location,
            "limit",
            5,
            "maxPages",
            2
        );
    }

    private Map<String, Object> row(
        boolean enabled,
        Duration delay,
        List<Map<String, Object>> sources,
        long version) {
        Map<String, Object> row = new HashMap<>();
        row.put("enabled", enabled);
        row.put("fixed_delay_millis", delay.toMillis());
        row.put("actor_id", actor);
        row.put("sources_json", sources);
        row.put("version", version);
        row.put("updated_by", actor);
        row.put("updated_at", Timestamp.from(UPDATED_AT));
        return row;
    }
}
