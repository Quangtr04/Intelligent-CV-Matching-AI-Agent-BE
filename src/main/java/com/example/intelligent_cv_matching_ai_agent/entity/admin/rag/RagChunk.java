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
 * Persistence model for the rag_chunks table; foreign keys retain their
 * explicit UUID identifiers.
 */
@Entity
@Table(name = "rag_chunks")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class RagChunk {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("document_id")
    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @JsonProperty("chunk_index")
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @JsonProperty("content")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @JsonProperty("token_count")
    @Column(name = "token_count", nullable = true)
    private Integer tokenCount;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
