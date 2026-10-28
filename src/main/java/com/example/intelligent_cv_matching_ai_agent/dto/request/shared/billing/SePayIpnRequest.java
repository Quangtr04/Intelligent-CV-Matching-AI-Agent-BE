package com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Gateway notification parsed only after the configured IPN secret has been authenticated. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SePayIpnRequest(
    Long timestamp,
    @JsonProperty("notification_type")
    String notificationType,
    Order order,
    Transaction transaction,
    Customer customer
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Order(
        String id,
        @JsonProperty("order_id")
        String orderId,
        @JsonProperty("order_status")
        String orderStatus,
        @JsonProperty("order_currency")
        String orderCurrency,
        @JsonProperty("order_amount")
        BigDecimal orderAmount,
        @JsonProperty("order_invoice_number")
        String orderInvoiceNumber
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transaction(
        String id,
        @JsonProperty("transaction_id")
        String transactionId,
        @JsonProperty("transaction_type")
        String transactionType,
        @JsonProperty("transaction_status")
        String transactionStatus,
        @JsonProperty("transaction_amount")
        BigDecimal transactionAmount,
        @JsonProperty("transaction_currency")
        String transactionCurrency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(
        String id,
        @JsonProperty("customer_id")
        String customerId
    ) {
    }
}
