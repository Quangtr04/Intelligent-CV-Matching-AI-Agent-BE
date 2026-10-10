package com.example.intelligent_cv_matching_ai_agent.entity.shared.support;

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
 * Persistence model for the support_tickets table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "support_tickets")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class SupportTicket {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("assigned_admin_id")
    @Column(name = "assigned_admin_id", nullable = true)
    private UUID assignedAdminId;

    @JsonProperty("subject")
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @JsonProperty("category")
    @Column(name = "category", nullable = true, length = 50)
    private String category;

    @JsonProperty("description")
    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("priority")
    @Column(name = "priority", nullable = true, length = 20)
    private String priority;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

    @JsonProperty("resolved_at")
    @Column(name = "resolved_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime resolvedAt;

}
