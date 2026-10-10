package com.example.intelligent_cv_matching_ai_agent.controller.guest.identity;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestSessionService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/guest")
public class GuestController {

    private final GuestSessionService service;

    public GuestController(
        GuestSessionService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    public Map<String, Object> create(
        HttpServletRequest request) {
        return service.create(request.getRemoteAddr());
    }

    @GetMapping("/sessions/me")
    public Map<String, Object> me(
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.me(key);
    }

    @GetMapping("/demo-resumes")
    public java.util.List<Map<String, Object>> demos(
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.demos(key);
    }

    @GetMapping("/templates")
    public java.util.List<Map<String, Object>> templates(
        @RequestHeader("X-Guest-Session")
        String key) {
        return service.templates(key);
    }
}
