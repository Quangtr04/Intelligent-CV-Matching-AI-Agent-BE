package com.example.intelligent_cv_matching_ai_agent.entity.admin.monitoring;

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
 * Persistence model for the vector_store_metrics table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "vector_store_metrics")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class VectorStoreMetric {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("provider")
    @Column(name = "provider", nullable = true, length = 100)
    private String provider;

    @JsonProperty("index_name")
    @Column(name = "index_name", nullable = true, length = 150)
    private String indexName;

    @JsonProperty("vector_count")
    @Column(name = "vector_count", nullable = true)
    private Long vectorCount;

    @JsonProperty("storage_size_bytes")
    @Column(name = "storage_size_bytes", nullable = true)
    private Long storageSizeBytes;

    @JsonProperty("response_time_ms")
    @Column(name = "response_time_ms", nullable = true)
    private Long responseTimeMs;

    @JsonProperty("status")
    @Column(name = "status", nullable = true, length = 50)
    private String status;

    @JsonProperty("measured_at")
    @Column(name = "measured_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime measuredAt;

}
