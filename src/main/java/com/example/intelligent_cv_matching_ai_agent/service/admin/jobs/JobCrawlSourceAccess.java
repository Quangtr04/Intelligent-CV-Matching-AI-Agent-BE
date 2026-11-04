package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared pacing and temporary source pauses for manual and scheduled crawls in this application instance.
 * State is deliberately in memory: a restart clears it; multiple application instances need a shared coordinator.
 */
@Component
public class JobCrawlSourceAccess {

    private final Duration requestDelay;

    private final Duration rateLimitCooldown;

    private final Duration challengeCooldown;

    private final Clock clock;

    private final Sleeper sleeper;

    private final Map<String, SourceState> states = Map.of("ITVIEC", new SourceState(), "TOPCV", new SourceState());

    public JobCrawlSourceAccess() {
        this(Duration.ofSeconds(3), Duration.ofMinutes(5), Duration.ofMinutes(15));
    }

    @Autowired
    public JobCrawlSourceAccess(
        @Value("${app.jobs.crawl.request-delay:PT3S}")
        Duration requestDelay,
        @Value("${app.jobs.crawl.rate-limit-cooldown:PT5M}")
        Duration rateLimitCooldown,
        @Value("${app.jobs.crawl.challenge-cooldown:PT15M}")
        Duration challengeCooldown) {
        this(requestDelay, rateLimitCooldown, challengeCooldown, Clock.systemUTC(), Thread::sleep);
    }

    JobCrawlSourceAccess(
        Duration requestDelay,
        Duration rateLimitCooldown,
        Duration challengeCooldown,
        Clock clock,
        Sleeper sleeper) {
        this.requestDelay = requireDuration(requestDelay, true, "request-delay");
        this.rateLimitCooldown = requireDuration(rateLimitCooldown, false, "rate-limit-cooldown");
        this.challengeCooldown = requireDuration(challengeCooldown, false, "challenge-cooldown");
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public Lease open(
        String source) {
        String name = sourceName(source);
        SourceState state = states.get(name);
        synchronized (state) {
            Instant now = clock.instant();
            if (state.cooldownUntil.isAfter(now)) {
                throw new ApiException(429, cooldownMessage(name, state.cooldownUntil));
            }
            if (state.activeToken != null) {
                throw ApiException.conflict("Source " + name + " already has an active crawl");
            }
            UUID token = UUID.randomUUID();
            state.activeToken = token;
            return new Lease(state, token);
        }
    }

    /** Called immediately before every HTTP attempt, including redirects. Does not sleep through a cooldown. */
    public void beforeRequest(
        String source)
        throws IOException {
        String name = sourceName(source);
        SourceState state = states.get(name);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted(null);
            }
            Duration wait;
            synchronized (state) {
                Instant now = clock.instant();
                if (state.cooldownUntil.isAfter(now)) {
                    throw new CooldownException(name, state.cooldownUntil);
                }
                if (!state.nextRequestAt.isAfter(now)) {
                    state.nextRequestAt = add(now, requestDelay);
                    return;
                }
                wait = Duration.between(now, state.nextRequestAt);
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw interrupted(ex);
            }
            // A different caller may have used the slot or paused the source while this caller slept.
        }
    }

    /** Extends a source pause without sending another request or delaying this thread. */
    public Instant blocked(
        String source,
        String retryAfter,
        boolean challenge) {
        SourceState state = states.get(sourceName(source));
        synchronized (state) {
            Instant now = clock.instant();
            Instant until = add(now, challenge ? challengeCooldown : rateLimitCooldown);
            Instant requested = retryAfter(now, retryAfter);
            if (requested != null && requested.isAfter(until)) {
                until = requested;
            }
            if (state.cooldownUntil.isAfter(until)) {
                until = state.cooldownUntil;
            }
            state.cooldownUntil = until;
            return until;
        }
    }

    private static Instant retryAfter(
        Instant now,
        String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String header = value.strip();
        if (header.matches("[0-9]+")) {
            try {
                return add(now, Duration.ofSeconds(Long.parseLong(header)));
            } catch (NumberFormatException ex) {
                // A valid, enormous delta must not accidentally shorten the server's requested pause.
                return Instant.MAX;
            }
        }
        try {
            Instant date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return date.isAfter(now) ? date : null;
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static Instant add(
        Instant value,
        Duration duration) {
        try {
            return value.plus(duration);
        } catch (DateTimeException | ArithmeticException ex) {
            return Instant.MAX;
        }
    }

    private static Duration requireDuration(
        Duration value,
        boolean zeroAllowed,
        String name) {
        if (value == null || value.isNegative() || !zeroAllowed && value.isZero()) {
            throw new IllegalArgumentException(
                "Job crawl " + name + " must be " + (zeroAllowed ? "nonnegative" : "positive")
            );
        }
        return value;
    }

    private static String sourceName(
        String source) {
        String value = source == null ? "" : source.strip().toUpperCase(Locale.ROOT);
        if (!value.equals("ITVIEC") && !value.equals("TOPCV")) {
            throw ApiException.badRequest("sourceSite must be ITVIEC or TOPCV");
        }
        return value;
    }

    private static String cooldownMessage(
        String source,
        Instant until) {
        return "Source " + source + " is cooling down until " + until + "; no source request was sent";
    }

    private static InterruptedIOException interrupted(
        InterruptedException cause) {
        InterruptedIOException failure = new InterruptedIOException("Crawl request pacing was interrupted");
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(
            Duration duration)
            throws InterruptedException;
    }

    private static final class SourceState {

        private UUID activeToken;

        private Instant nextRequestAt = Instant.MIN;

        private Instant cooldownUntil = Instant.MIN;
    }

    public static final class Lease implements AutoCloseable {

        private final SourceState state;

        private final UUID token;

        private Lease(
            SourceState state,
            UUID token) {
            this.state = state;
            this.token = token;
        }

        @Override
        public void close() {
            synchronized (state) {
                if (token.equals(state.activeToken)) {
                    state.activeToken = null;
                }
            }
        }
    }

    public static final class CooldownException extends IOException {

        private final Instant retryAt;

        private CooldownException(
            String source,
            Instant retryAt) {
            super(cooldownMessage(source, retryAt));
            this.retryAt = retryAt;
        }

        public Instant retryAt() {
            return retryAt;
        }
    }
}
