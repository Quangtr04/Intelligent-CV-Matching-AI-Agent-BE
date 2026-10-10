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
 * Persistence model for the roadmap_nodes table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "roadmap_nodes")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class RoadmapNode {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("roadmap_id")
    @Column(name = "roadmap_id", nullable = false)
    private UUID roadmapId;

    @JsonProperty("skill_id")
    @Column(name = "skill_id", nullable = true)
    private UUID skillId;

    @JsonProperty("skill_name")
    @Column(name = "skill_name", nullable = false, length = 150)
    private String skillName;

    @JsonProperty("description")
    @Column(name = "description", nullable = true, columnDefinition = "text")
    private String description;

    @JsonProperty("sequence_order")
    @Column(name = "sequence_order", nullable = false)
    private Short sequenceOrder;

    @JsonProperty("estimated_hours")
    @Column(name = "estimated_hours", nullable = true)
    private Integer estimatedHours;

    @JsonProperty("is_prerequisite")
    @Column(name = "is_prerequisite", nullable = false)
    private Boolean isPrerequisite;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @JsonProperty("completed_at")
    @Column(name = "completed_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime completedAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
