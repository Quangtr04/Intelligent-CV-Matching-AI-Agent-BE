package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationRatingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantBusyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ConsultationBookingController {

    private final ConsultationBookingService service;

    public ConsultationBookingController(
        ConsultationBookingService service) {
        this.service = service;
    }

    @GetMapping("/consultants")
    public List<Map<String, Object>> consultants(
        @RequestParam(defaultValue = "")
        String targetRole) {
        return service.consultants(targetRole);
    }

    @GetMapping("/consultants/{id}/availability")
    public List<Map<String, Object>> availability(
        @PathVariable
        UUID id,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        OffsetDateTime from,
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        OffsetDateTime to) {
        return service.availability(id, from, to);
    }

    @GetMapping("/consultants/me/profile")
    public Map<String, Object> profile() {
        return service.profile();
    }

    @PutMapping("/consultants/me/profile")
    public Map<String, Object> saveProfile(
        @Valid
        @RequestBody
        ConsultantProfileRequest input) {
        return service.saveProfile(input);
    }

    @GetMapping("/consultants/me/busy")
    public List<Map<String, Object>> busy() {
        return service.busy();
    }

    @PostMapping("/consultants/me/busy")
    public Map<String, Object> addBusy(
        @Valid
        @RequestBody
        ConsultantBusyRequest input) {
        return service.addBusy(input);
    }

    @DeleteMapping("/consultants/me/busy/{id}")
    public Map<String, Object> removeBusy(
        @PathVariable
        UUID id) {
        return service.removeBusy(id);
    }

    @GetMapping("/consultation-bookings")
    public List<Map<String, Object>> bookings() {
        return service.bookings();
    }

    @PostMapping("/consultation-bookings")
    public Map<String, Object> create(
        @Valid
        @RequestBody
        ConsultationBookingRequest input) {
        return service.create(input);
    }

    @PostMapping("/consultation-bookings/{id}/accept")
    public Map<String, Object> accept(
        @PathVariable
        UUID id) {
        return service.accept(id);
    }

    @PostMapping("/consultation-bookings/{id}/decline")
    public Map<String, Object> decline(
        @PathVariable
        UUID id) {
        return service.decline(id);
    }

    @PostMapping("/consultation-bookings/{id}/meeting/retry")
    public Map<String, Object> retryMeeting(
        @PathVariable
        UUID id) {
        return service.retryMeeting(id);
    }

    @PostMapping("/consultation-bookings/{id}/cancel")
    public Map<String, Object> cancel(
        @PathVariable
        UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/consultation-bookings/{id}/complete")
    public Map<String, Object> complete(
        @PathVariable
        UUID id) {
        return service.complete(id);
    }

    @PostMapping("/consultation-bookings/{id}/rating")
    public Map<String, Object> rate(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ConsultationRatingRequest input) {
        return service.rate(id, input);
    }
}
