package com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
public class JobCollectionRepository {

    private final SqlStore db;

    public JobCollectionRepository(
        SqlStore db) {
        this.db = db;
    }

    public boolean isActiveAdministrator(
        UUID actor) {
        return actor != null && Boolean.TRUE.equals(
            db.scalar(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND role='ADMIN' "
                    + "AND status='ACTIVE' AND deleted_at IS NULL)",
                actor
            )
        );
    }

    public boolean acquireLease(
        String name,
        UUID token,
        Duration leaseDuration,
        Duration minimumInterval) {
        return acquireLease(name, token, leaseDuration, minimumInterval, false);
    }

    public boolean acquireCollectionLease(
        String name,
        UUID token,
        Duration leaseDuration,
        Duration minimumInterval) {
        return acquireLease(name, token, leaseDuration, minimumInterval, true);
    }

    private boolean acquireLease(
        String name,
        UUID token,
        Duration leaseDuration,
        Duration minimumInterval,
        boolean waitAfterCompletion) {
        String previousRun = waitAfterCompletion
            ? "GREATEST(job_collection_leases.last_started_at,job_collection_leases.last_completed_at)"
            : "job_collection_leases.last_started_at";
        return db.optional(
            "INSERT INTO job_collection_leases(name,lease_token,lease_until,last_started_at,last_error) "
                + "VALUES(?,?,now()+(? * interval '1 millisecond'),now(),NULL) ON CONFLICT(name) DO UPDATE "
                + "SET lease_token=EXCLUDED.lease_token,lease_until=EXCLUDED.lease_until,"
                + "last_started_at=EXCLUDED.last_started_at,last_error=NULL "
                + "WHERE (job_collection_leases.lease_until IS NULL OR job_collection_leases.lease_until<=now()) "
                + "AND (" + previousRun + " IS NULL " + "OR " + previousRun
                + "<=now()-(? * interval '1 millisecond')) RETURNING name",
            name,
            token,
            leaseDuration.toMillis(),
            minimumInterval.toMillis()
        ).isPresent();
    }

    public boolean completeLease(
        String name,
        UUID token,
        String error) {
        String bounded = error == null ? null : error.substring(0, Math.min(4000, error.length()));
        return db.update(
            "UPDATE job_collection_leases SET lease_until=now(),last_completed_at=now(),last_error=? "
                + "WHERE name=? AND lease_token=?",
            bounded,
            name,
            token
        ) == 1;
    }

    public record ExpirationResult(
        int expiredSources,
        int expiredJobs,
        int invalidatedEmbeddings
    ) {
    }

    @Transactional
    public ExpirationResult expireSources(
        int batchSize) {
        List<UUID> jobs = db.list(
            "SELECT j.id FROM market_job_posts j WHERE EXISTS "
                + "(SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                + "AND s.valid_through<=now()) OR (j.status='ACTIVE' AND j.valid_through<=now() AND NOT EXISTS "
                + "(SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                + "AND (s.valid_through IS NULL OR s.valid_through>now()))) "
                + "ORDER BY j.id LIMIT ? FOR UPDATE OF j SKIP LOCKED",
            Math.max(1, Math.min(batchSize, 1000))
        ).stream().map(row -> (UUID) row.get("id")).toList();

        int sources = 0, expired = 0, embeddings = 0;
        for (UUID id : jobs) {
            sources += db.update(
                "UPDATE market_job_sources SET status='EXPIRED' WHERE market_job_id=? "
                    + "AND status='ACTIVE' AND valid_through<=now()",
                id
            );
            int changed = db.update(
                "UPDATE market_job_posts j SET status='EXPIRED' WHERE id=? AND status='ACTIVE' AND NOT EXISTS "
                    + "(SELECT 1 FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                    + "AND (s.valid_through IS NULL OR s.valid_through>now()))",
                id
            );
            expired += changed;
            db.update(
                "UPDATE market_job_posts j SET valid_through=(SELECT CASE WHEN count(*)=0 THEN j.valid_through "
                    + "WHEN count(*) FILTER (WHERE s.valid_through IS NULL)>0 THEN NULL ELSE max(s.valid_through) END "
                    + "FROM market_job_sources s WHERE s.market_job_id=j.id AND s.status='ACTIVE' "
                    + "AND (s.valid_through IS NULL OR s.valid_through>now())) WHERE j.id=?",
                id
            );
            if (changed > 0) {
                embeddings += db.update(
                    "UPDATE market_job_embeddings SET is_current=false WHERE market_job_id=? AND is_current=true",
                    id
                );
            }
        }
        return new ExpirationResult(sources, expired, embeddings);
    }
}
