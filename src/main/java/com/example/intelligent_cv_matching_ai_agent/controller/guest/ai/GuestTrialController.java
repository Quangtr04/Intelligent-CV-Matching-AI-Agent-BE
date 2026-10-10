package com.example.intelligent_cv_matching_ai_agent.controller.guest.ai;

import com.example.intelligent_cv_matching_ai_agent.service.guest.ai.GuestTrialService;

import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestBuildRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestRecommendationRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/guest/trials")
public class GuestTrialController {

    private final GuestTrialService service;

    public GuestTrialController(
        GuestTrialService service) {
        this.service = service;
    }

    @PostMapping("/build")
    public Object build(
        @RequestHeader("X-Guest-Session")
        String key,
        @Valid
        @RequestBody
        GuestBuildRequest request) {
        return service.build(key, request);
    }

    @PostMapping("/recommendations")
    public Object recommend(
        @RequestHeader("X-Guest-Session")
        String key,
        @Valid
        @RequestBody
        GuestRecommendationRequest request) {
        return service.recommend(key, request);
    }
}
