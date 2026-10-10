package com.example.intelligent_cv_matching_ai_agent.entity.candidate.resume;

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
 * Persistence model for the resumes table; foreign keys retain their explicit
 * UUID identifiers.
 */
@Entity
@Table(name = "resumes")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Resume {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = true)
    private UUID userId;

    @JsonProperty("template_id")
    @Column(name = "template_id", nullable = true)
    private UUID templateId;

    @JsonProperty("title")
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JsonProperty("source")
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JsonProperty("is_demo")
    @Column(name = "is_demo", nullable = false)
    private Boolean isDemo;

    @JsonProperty("is_primary")
    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary;

    @JsonProperty("language")
    @Column(name = "language", nullable = true, length = 20)
    private String language;

    @JsonProperty("file_name")
    @Column(name = "file_name", nullable = true, length = 255)
    private String fileName;

    @JsonProperty("file_url")
    @Column(name = "file_url", nullable = true, columnDefinition = "text")
    private String fileUrl;

    @JsonProperty("mime_type")
    @Column(name = "mime_type", nullable = true, length = 100)
    private String mimeType;

    @JsonProperty("file_size_bytes")
    @Column(name = "file_size_bytes", nullable = true)
    private Long fileSizeBytes;

    @JsonProperty("raw_content")
    @Column(name = "raw_content", nullable = true, columnDefinition = "text")
    private String rawContent;

    @JsonProperty("structured_data")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_data", nullable = true, columnDefinition = "jsonb")
    private Object structuredData;

    @JsonProperty("content_hash")
    @Column(name = "content_hash", nullable = true, length = 64)
    private String contentHash;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;

}
