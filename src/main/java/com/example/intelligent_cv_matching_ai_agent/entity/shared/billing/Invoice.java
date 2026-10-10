package com.example.intelligent_cv_matching_ai_agent.entity.shared.billing;

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
 * Persistence model for the invoices table; foreign keys retain their explicit
 * UUID identifiers.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@DynamicInsert
@DynamicUpdate
public class Invoice {

    @JsonProperty("id")
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false)
    private UUID id;

    @JsonProperty("user_id")
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JsonProperty("payment_order_id")
    @Column(name = "payment_order_id", nullable = false)
    private UUID paymentOrderId;

    @JsonProperty("invoice_number")
    @Column(name = "invoice_number", nullable = false, length = 100)
    private String invoiceNumber;

    @JsonProperty("subtotal")
    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @JsonProperty("tax_amount")
    @Column(name = "tax_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @JsonProperty("total_amount")
    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @JsonProperty("currency")
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @JsonProperty("status")
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @JsonProperty("issued_at")
    @Column(name = "issued_at", nullable = false, columnDefinition = "timestamptz")
    private OffsetDateTime issuedAt;

    @JsonProperty("paid_at")
    @Column(name = "paid_at", nullable = true, columnDefinition = "timestamptz")
    private OffsetDateTime paidAt;

    @JsonProperty("invoice_url")
    @Column(name = "invoice_url", nullable = true, columnDefinition = "text")
    private String invoiceUrl;

}
