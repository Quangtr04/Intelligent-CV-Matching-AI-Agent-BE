package com.example.intelligent_cv_matching_ai_agent.controller.admin.management;

import com.example.intelligent_cv_matching_ai_agent.service.admin.management.ManagerOperationsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manager")
public class ManagerOperationsController {

    private final ManagerOperationsService service;

    public ManagerOperationsController(
        ManagerOperationsService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return service.dashboard();
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to) {
        return service.analytics(from, to);
    }

    @GetMapping("/subscriptions")
    public List<Map<String, Object>> subscriptions(
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "")
        String search,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.subscriptions(status, search, limit, offset);
    }

    @GetMapping("/subscriptions/{id}")
    public Map<String, Object> subscription(
        @PathVariable
        UUID id) {
        return service.subscription(id);
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> payments(
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "")
        String search,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.payments(status, search, limit, offset);
    }

    @GetMapping("/payments/{id}")
    public Map<String, Object> payment(
        @PathVariable
        UUID id) {
        return service.payment(id);
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "")
        String search,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.invoices(status, search, limit, offset);
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> invoice(
        @PathVariable
        UUID id) {
        return service.invoice(id);
    }

    @GetMapping("/usage")
    public List<Map<String, Object>> usage(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to,
        @RequestParam(required = false)
        String feature,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.usage(from, to, feature, limit, offset);
    }

    @GetMapping("/consultants")
    public List<Map<String, Object>> consultants(
        @RequestParam(defaultValue = "")
        String search,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.consultants(search, limit, offset);
    }

    @GetMapping("/consultants/{id}/bookings")
    public List<Map<String, Object>> consultantBookings(
        @PathVariable
        UUID id,
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.consultantBookings(id, status, limit, offset);
    }

    @GetMapping("/crawl-reports")
    public List<Map<String, Object>> crawlReports(
        @RequestParam(required = false)
        String source,
        @RequestParam(required = false)
        String status,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.crawlReports(source, status, limit, offset);
    }

    @GetMapping("/feedback")
    public List<Map<String, Object>> feedback(
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.feedback(limit, offset);
    }
}
