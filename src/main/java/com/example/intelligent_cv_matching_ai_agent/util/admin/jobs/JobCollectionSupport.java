package com.example.intelligent_cv_matching_ai_agent.util.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.util.shared.identity.TokenSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import java.util.Locale;

/** Stable names for scheduled collection leases. */
public final class JobCollectionSupport {

    private JobCollectionSupport() {
    }

    public static String leaseName(
        CrawlSourceSettingsResponse source) {
        String identity = String.join(
            "\n",
            source.sourceSite().strip().toUpperCase(Locale.ROOT),
            source.keyword().strip().toLowerCase(Locale.ROOT),
            source.roleCategory().strip().toLowerCase(Locale.ROOT),
            JobNormalizer.fold(JobNormalizer.normalizeLocationFilter(source.location()))
        );
        return "job-collection:" + TokenSupport.hash(identity);
    }
}
