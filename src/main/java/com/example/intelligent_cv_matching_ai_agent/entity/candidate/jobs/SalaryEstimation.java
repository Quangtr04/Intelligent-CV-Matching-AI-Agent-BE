package com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs;

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
 * Persistence model for the salary_estimations table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "salary_estimations")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SalaryEstimation {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("market_job_id")
    @Column(name = "market_job_id", nullable = true)
    private UUID marketJobId;

    @JsonProperty("target_job_title")
    @Column(name = "target_job_title", nullable = true, length = 255)
    private String targetJobTitle;

    @JsonProperty("estimated_min")
    @Column(name = "estimated_min", nullable = true, precision = 14, scale = 2)
    private BigDecimal estimatedMin;

    @JsonProperty("estimated_max")
    @Column(name = "estimated_max", nullable = true, precision = 14, scale = 2)
    private BigDecimal estimatedMax;

    @JsonProperty("currency")
    @Column(name = "currency", nullable = true, length = 10)
    private String currency;

    @JsonProperty("reasoning")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasoning", nullable = true, columnDefinition = "jsonb")
    private Object reasoning;

    @JsonProperty("model_name")
    @Column(name = "model_name", nullable = true, length = 100)
    private String modelName;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
