package com.example.intelligent_cv_matching_ai_agent.service.shared.resume;

public interface ResumeFileStorage {

    void store(
        String key,
        byte[] content,
        String contentType);

    byte[] read(
        String key);

    boolean exists(
        String key,
        long maxBytes);

    void delete(
        String key);
}
