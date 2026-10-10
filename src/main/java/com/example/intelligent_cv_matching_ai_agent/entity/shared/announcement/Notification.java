package com.example.intelligent_cv_matching_ai_agent.entity.shared.announcement;

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
 * Persistence model for the notifications table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Notification {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("type")
    @Column(name = "type", nullable = true, length = 100)
    private String type;

    @JsonProperty("title")
    @Column(name = "title", nullable = true, length = 255)
    private String title;

    @JsonProperty("content")
    @Column(name = "content", nullable = true, columnDefinition = "text")
    private String content;

    @JsonProperty("reference_type")
    @Column(name = "reference_type", nullable = true, length = 100)
    private String referenceType;

    @JsonProperty("reference_id")
    @Column(name = "reference_id", nullable = true)
    private UUID referenceId;

    @JsonProperty("is_read")
    @Column(name = "is_read", nullable = false)
    private Boolean isRead;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("read_at")
    @Column(name = "read_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime readAt;

}
