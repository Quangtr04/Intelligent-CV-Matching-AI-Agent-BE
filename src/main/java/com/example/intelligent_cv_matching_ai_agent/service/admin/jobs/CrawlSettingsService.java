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
import com.example.intelligent_cv_matching_ai_agent.util.admin.jobs.CrawlSettingsSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlSettingsService {

    private final CrawlSettingsRepository db;

    private final JobCollectionProperties configuration;

    private final JobCollectionRepository collection;

    private final CurrentUser current;

    public CrawlSettingsService(
        CrawlSettingsRepository db,
        JobCollectionProperties configuration,
        JobCollectionRepository collection,
        CurrentUser current) {
        this.db = db;
        this.configuration = configuration;
        this.collection = collection;
        this.current = current;
    }

    public CrawlSettingsResponse get() {
        current.requireRole("ADMIN");
        return effective();
    }

    @Transactional
    public CrawlSettingsResponse update(
        CrawlSettingsRequest request) {
        current.requireRole("ADMIN");
        if (request == null || request.enabled() == null || request.sources() == null
            || request.sources().size() > 20) {
            throw ApiException.badRequest("enabled and at most 20 sources are required");
        }
        Duration delay = CrawlSettingsSupport.fixedDelay(request.fixedDelay());
        UUID updatedBy = current.id();
        UUID actor = request.actorId() == null ? updatedBy : request.actorId();
        if (!collection.isActiveAdministrator(actor)) {
            throw ApiException.badRequest("actorId must identify an active administrator");
        }

        List<CrawlSourceSettingsResponse> sources = new ArrayList<>();
        Set<List<String>> identities = new HashSet<>();
        for (CrawlSourceSettingsRequest source : request.sources()) {
            CrawlSourceSettingsResponse normalized = CrawlSettingsSupport.normalize(source);
            List<String> identity = List.of(
                normalized.sourceSite(),
                normalized.keyword().toLowerCase(Locale.ROOT),
                normalized.roleCategory().toLowerCase(Locale.ROOT),
                JobNormalizer.fold(normalized.location())
            );
            if (!identities.add(identity)) {
                throw ApiException.badRequest("Duplicate crawl sources are not allowed");
            }
            sources.add(normalized);
        }
        if (request.enabled() && sources.isEmpty()) {
            throw ApiException.badRequest("Enabled collection requires at least one source");
        }

        Map<String, Object> saved = db.save(request.enabled(), delay, actor, List.copyOf(sources), updatedBy);
        db.audit(updatedBy);
        return CrawlSettingsSupport.fromDatabase(saved);
    }

    /** Background collection reads a fresh immutable snapshot without a request security context. */
    public CrawlSettingsResponse effective() {
        return db.find().map(CrawlSettingsSupport::fromDatabase)
            .orElseGet(() -> CrawlSettingsSupport.fromEnvironment(configuration));
    }
}
