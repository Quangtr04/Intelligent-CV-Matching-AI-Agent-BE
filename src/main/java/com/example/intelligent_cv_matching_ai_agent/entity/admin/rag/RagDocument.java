package com.example.intelligent_cv_matching_ai_agent.entity.admin.rag;

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
 * Persistence model for the rag_documents table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "rag_documents")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class RagDocument {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("uploaded_by")
    @Column(name = "uploaded_by", nullable = true)
    private UUID uploadedBy;

    @JsonProperty("title")
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JsonProperty("source_type")
    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @JsonProperty("file_url")
    @Column(name = "file_url", nullable = true, columnDefinition = "text")
    private String fileUrl;

    @JsonProperty("related_skill_ids")
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "related_skill_ids", nullable = true, columnDefinition = "uuid[]")
    private UUID[] relatedSkillIds;

    @JsonProperty("metadata")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = true, columnDefinition = "jsonb")
    private Object metadata;

    @JsonProperty("chunks_count")
    @Column(name = "chunks_count", nullable = false)
    private Integer chunksCount;

    @JsonProperty("embedding_status")
    @Column(name = "embedding_status", nullable = false, length = 32)
    private String embeddingStatus;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
