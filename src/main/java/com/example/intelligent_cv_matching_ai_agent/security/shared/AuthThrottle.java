package com.example.intelligent_cv_matching_ai_agent.security.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance abuse protection. Use a shared gateway limiter when running
 * multiple replicas.
 */
@Component
public class AuthThrottle {

    private record Window(
        long start,
        int count
    ) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void check(
        String key,
        int limit,
        long seconds) {
        long now = Instant.now().getEpochSecond();

        if (windows.size() > 10000)
            windows.entrySet().removeIf(e -> now - e.getValue().start() > 3600);

        Window window = windows.compute(
            key,
            (
                k,
                old) -> old == null || now - old.start() >= seconds
                    ? new Window(now, 1)
                    : new Window(old.start(), old.count() + 1)
        );
        if (window.count() > limit)
            throw new ApiException(429, "Too many attempts; please retry later");
    }
}
