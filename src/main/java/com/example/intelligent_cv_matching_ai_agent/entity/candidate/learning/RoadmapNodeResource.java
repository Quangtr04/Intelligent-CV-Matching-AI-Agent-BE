package com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning;

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
 * Persistence model for the roadmap_node_resources table; foreign keys retain
 * their explicit UUID identifiers.
 */
@Entity
@Table(name = "roadmap_node_resources")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
@IdClass(RoadmapNodeResourceId.class)
public class RoadmapNodeResource {

    @JsonProperty("roadmap_node_id")
    @Id
    @Column(name = "roadmap_node_id", nullable = false)
    private UUID roadmapNodeId;

    @JsonProperty("resource_id")
    @Id
    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @JsonProperty("sequence_order")
    @Column(name = "sequence_order", nullable = true)
    private Short sequenceOrder;

    @JsonProperty("relevance_score")
    @Column(name = "relevance_score", nullable = true, precision = 5, scale = 2)
    private BigDecimal relevanceScore;

    @JsonProperty("recommendation_reason")
    @Column(name = "recommendation_reason", nullable = true, columnDefinition = "text")
    private String recommendationReason;

    @JsonProperty("is_primary")
    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
