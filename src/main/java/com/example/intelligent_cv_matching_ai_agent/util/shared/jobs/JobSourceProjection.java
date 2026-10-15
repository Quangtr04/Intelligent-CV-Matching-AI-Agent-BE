package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

/** Source projections use the trusted job alias j in repository queries. */
public final class JobSourceProjection {

    public static final String ACTIVE_URL = activeSource("source_url");

    public static final String ACTIVE_SITE = activeSource("source_site");

    private JobSourceProjection() {
    }

    private static String activeSource(
        String column) {
        return ("CASE WHEN NOT EXISTS(SELECT 1 FROM market_job_sources known WHERE known.market_job_id=j.id) "
            + "THEN j.")
            + column + " ELSE (SELECT s." + column
            + (" FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                + "AND (s.valid_through IS NULL OR s.valid_through>now()) "
                + "ORDER BY (s.source_url=j.source_url) DESC NULLS LAST,s.last_seen_at DESC,s.id LIMIT 1) END");
    }
}
