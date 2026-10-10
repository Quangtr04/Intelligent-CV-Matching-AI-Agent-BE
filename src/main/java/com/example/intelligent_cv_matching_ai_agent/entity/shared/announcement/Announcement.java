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
 * Persistence model for the announcements table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Announcement {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("created_by_user_id")
    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;

    @JsonProperty("title")
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JsonProperty("content")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("publish_at")
    @Column(name = "publish_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime publishAt;

    @JsonProperty("expires_at")
    @Column(name = "expires_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime expiresAt;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
