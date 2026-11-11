package com.example.intelligent_cv_matching_ai_agent.entity.admin.jobs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Persistence model for the market_crawl_runs table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "market_crawl_runs")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class MarketCrawlRun {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("source_site")
    @Column(name = "source_site", nullable = false, length = 50)
    private String sourceSite;

    @JsonProperty("keyword")
    @Column(name = "keyword", nullable = false, length = 255)
    private String keyword;

    @JsonProperty("role_category")
    @Column(name = "role_category", nullable = false, length = 100)
    private String roleCategory;

    @JsonProperty("requested_location")
    @Column(name = "requested_location", nullable = true, length = 120)
    private String requestedLocation;

    @JsonProperty("requested_limit")
    @Column(name = "requested_limit", nullable = false)
    private Integer requestedLimit;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JsonProperty("message")
    @Column(name = "message", nullable = true, columnDefinition = "text")
    private String message;

    @JsonProperty("fetched_urls")
    @Column(name = "fetched_urls", nullable = false)
    private Integer fetchedUrls;

    @JsonProperty("parsed_jobs")
    @Column(name = "parsed_jobs", nullable = false)
    private Integer parsedJobs;

    @JsonProperty("imported_jobs")
    @Column(name = "imported_jobs", nullable = false)
    private Integer importedJobs;

    @JsonProperty("imported_skills")
    @Column(name = "imported_skills", nullable = false)
    private Integer importedSkills;

    @JsonProperty("skipped_duplicates")
    @Column(name = "skipped_duplicates", nullable = false)
    private Integer skippedDuplicates;

    @JsonProperty("search_pages_checked")
    @Column(name = "search_pages_checked", nullable = false)
    private Integer searchPagesChecked;

    @JsonProperty("candidate_urls_discovered")
    @Column(name = "candidate_urls_discovered", nullable = false)
    private Integer candidateUrlsDiscovered;

    @JsonProperty("detail_pages_checked")
    @Column(name = "detail_pages_checked", nullable = false)
    private Integer detailPagesChecked;

    @JsonProperty("wrong_region_pages")
    @Column(name = "wrong_region_pages", nullable = false)
    private Integer wrongRegionPages;

    @JsonProperty("rejected_pages")
    @Column(name = "rejected_pages", nullable = false)
    private Integer rejectedPages;

    @JsonProperty("started_at")
    @Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime startedAt;

    @JsonProperty("finished_at")
    @Column(name = "finished_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime finishedAt;

    @JsonProperty("requested_by")
    @Column(name = "requested_by")
    private UUID requestedBy;

}
