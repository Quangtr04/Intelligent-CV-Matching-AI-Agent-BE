package com.example.intelligent_cv_matching_ai_agent.entity.admin.jobs;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Distributed schedule lease and latest execution monitoring. */
@Entity
@Table(name = "job_collection_leases")
@Getter
@Setter
@NoArgsConstructor
public class JobCollectionLease {

    @Id
    @Column(length = 100)
    private String name;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_until", columnDefinition = "timestamptz")
    private OffsetDateTime leaseUntil;

    @Column(name = "last_started_at", columnDefinition = "timestamptz")
    private OffsetDateTime lastStartedAt;

    @Column(name = "last_completed_at", columnDefinition = "timestamptz")
    private OffsetDateTime lastCompletedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
