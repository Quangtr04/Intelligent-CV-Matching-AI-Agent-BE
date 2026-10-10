package com.example.intelligent_cv_matching_ai_agent.entity.candidate.ai;

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
 * Persistence model for the career_assistant_message_sources table; foreign
 * keys retain their explicit UUID identifiers.
 */
@Entity
@Table(name = "career_assistant_message_sources")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class CareerAssistantMessageSource {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("message_id")
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @JsonProperty("rag_chunk_id")
    @Column(name = "rag_chunk_id", nullable = true)
    private UUID ragChunkId;

    @JsonProperty("rag_document_id")
    @Column(name = "rag_document_id", nullable = true)
    private UUID ragDocumentId;

    @JsonProperty("source_label")
    @Column(name = "source_label", nullable = true, length = 255)
    private String sourceLabel;

    @JsonProperty("similarity_score")
    @Column(name = "similarity_score", nullable = true, precision = 6, scale = 4)
    private BigDecimal similarityScore;

    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

}
