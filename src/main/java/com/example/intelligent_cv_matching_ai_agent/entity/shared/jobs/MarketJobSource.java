package com.example.intelligent_cv_matching_ai_agent.entity.shared.jobs;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.UuidGenerator;

/** Source provenance retained when identical jobs are merged across websites. */
@Entity
@Table(name = "market_job_sources")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
public class MarketJobSource {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "market_job_id", nullable = false)
    private UUID marketJobId;

    @Column(name = "source_site", nullable = false, length = 50)
    private String sourceSite;

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "valid_through", columnDefinition = "timestamptz")
    private OffsetDateTime validThrough;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime lastSeenAt;
}
