package com.example.intelligent_cv_matching_ai_agent.controller.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentReportRequest;

import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.BillingQueryService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BillingController {

    private final BillingQueryService service;

    public BillingController(
        BillingQueryService service) {
        this.service = service;
    }

    @GetMapping("/subscriptions/tiers")
    public List<Map<String, Object>> tiers() {
        return service.tiers();
    }

    @GetMapping("/subscriptions/me")
    public Map<String, Object> subscription() {
        return service.subscription();
    }

    @GetMapping("/subscriptions/history")
    public List<Map<String, Object>> history() {
        return service.history();
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public Map<String, Object> cancel(
        @PathVariable
        UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/payments/orders")
    public Map<String, Object> order(
        @Valid
        @RequestBody
        PaymentOrderRequest body) {
        return service.order(body);
    }

    @GetMapping("/payments/orders")
    public List<Map<String, Object>> orders() {
        return service.orders();
    }

    @GetMapping("/payments/orders/{id}")
    public Map<String, Object> order(
        @PathVariable
        UUID id) {
        return service.order(id);
    }

    @PostMapping("/payments/orders/{id}/reports")
    public Map<String, Object> report(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        PaymentReportRequest body) {
        return service.report(id, body);
    }

    @GetMapping("/payments/reports")
    public List<Map<String, Object>> reports() {
        return service.reports();
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices() {
        return service.invoices();
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> invoice(
        @PathVariable
        UUID id) {
        return service.invoice(id);
    }

    @PostMapping("/payments/webhook")
    public Map<String, Object> webhook(
        @RequestHeader("X-Payment-Timestamp")
        String timestamp,
        @RequestHeader("X-Payment-Signature")
        String signature,
        @RequestBody
        String body) {
        return service.webhook(timestamp, signature, body);
    }
}
