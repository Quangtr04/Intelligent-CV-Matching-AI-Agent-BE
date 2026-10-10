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
 * Persistence model for the market_crawl_items table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "market_crawl_items")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class MarketCrawlItem {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("crawl_run_id")
    @Column(name = "crawl_run_id", nullable = false)
    private UUID crawlRunId;

    @JsonProperty("source_url")
    @Column(name = "source_url", nullable = true, columnDefinition = "text")
    private String sourceUrl;

    @JsonProperty("item_status")
    @Column(name = "item_status", nullable = false, length = 30)
    private String itemStatus;

    @JsonProperty("http_status")
    @Column(name = "http_status", nullable = true)
    private Integer httpStatus;

    @JsonProperty("job_title")
    @Column(name = "job_title", nullable = true, length = 255)
    private String jobTitle;

    @JsonProperty("raw_content_hash")
    @Column(name = "raw_content_hash", nullable = true, length = 64)
    private String rawContentHash;

    @JsonProperty("message")
    @Column(name = "message", nullable = true, columnDefinition = "text")
    private String message;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
