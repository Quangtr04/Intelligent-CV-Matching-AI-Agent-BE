package com.example.intelligent_cv_matching_ai_agent.controller.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile.CareerProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PasswordChangeRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PersonalProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.identity.PersonalProfileResponse;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.ProfileService;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(
        ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public PersonalProfileResponse me() {
        return service.me();
    }

    @PutMapping
    public PersonalProfileResponse update(
        @Valid
        @RequestBody
        PersonalProfileRequest body) {
        return service.update(body);
    }

    @GetMapping("/career-profile")
    public Map<String, Object> career() {
        return service.career();
    }

    @PutMapping("/career-profile")
    public Map<String, Object> careerUpdate(
        @Valid
        @RequestBody
        CareerProfileRequest body) {
        return service.careerUpdate(body);
    }

    @DeleteMapping
    public Map<String, Object> deleteAccount() {
        return service.deleteAccount();
    }

    @PutMapping("/password")
    public Map<String, Object> password(
        @Valid
        @RequestBody
        PasswordChangeRequest body) {
        return service.password(body);
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return service.sessions();
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> revokeSession(
        @PathVariable
        UUID id) {
        return service.revokeSession(id);
    }
}
