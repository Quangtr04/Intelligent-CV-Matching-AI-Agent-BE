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
    public Object list() {
        return service.list();
    }

    @PostMapping("/manager/rating-appeals/{id}/decision")
    public Object decide(
        @PathVariable
        UUID id,
        @RequestBody
        Decision body) {
        return service.decide(id, body.decision(), body.reason());
    }
}
