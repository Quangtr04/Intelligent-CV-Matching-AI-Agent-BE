package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.RatingAppealService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RatingAppealController {

    private final RatingAppealService service;

    public RatingAppealController(
        RatingAppealService service) {
        this.service = service;
    }

    public record Submit(
        String reason,
        String evidenceUrl
    ) {
    }

    public record Decision(
        String decision,
        String reason
    ) {
    }

    @GetMapping("/consultation-bookings/{bookingId}/rating-appeal")
    public Object own(
        @PathVariable
        UUID bookingId) {
        return service.forBooking(bookingId);
    }

    @PostMapping("/consultation-bookings/{bookingId}/rating-appeal")
    public Object submit(
        @PathVariable
        UUID bookingId,
        @RequestBody
        Submit body) {
        return service.create(bookingId, body.reason(), body.evidenceUrl());
    }

    @GetMapping("/manager/rating-appeals")
    public Object list(
        @RequestParam(required = false)
        String status) {
        return service.listForManager(status);
    }

    @GetMapping("/manager/rating-appeals/{id}")
    public Object managerDetail(
        @PathVariable
        UUID id) {
        return service.detail(id);
    }

    @GetMapping("/manager/rating-appeals/{id}/history")
    public Object managerHistory(
        @PathVariable
        UUID id) {
        return service.history(id);
    }

    @PostMapping("/manager/rating-appeals/{id}/decision")
    public Object decide(
        @PathVariable
        UUID id,
        @RequestBody
        Decision body) {
        return service.decide(id, body.decision(), body.reason());
    }

    @GetMapping("/staff/rating-appeals")
    public Object staffList(
        @RequestParam(required = false)
        String status) {
        return service.list(status);
    }

    @GetMapping("/staff/rating-appeals/{id}")
    public Object staffDetail(
        @PathVariable
        UUID id) {
        return service.detail(id);
    }

    @GetMapping("/staff/rating-appeals/{id}/history")
    public Object staffHistory(
        @PathVariable
        UUID id) {
        return service.history(id);
    }

    @PostMapping("/staff/rating-appeals/{id}/preliminary-decision")
    public Object preliminaryDecide(
        @PathVariable
        UUID id,
        @RequestBody
        Decision body) {
        return service.preliminaryReview(id, body.decision(), body.reason());
    }
}
