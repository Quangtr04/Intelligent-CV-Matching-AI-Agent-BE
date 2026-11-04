package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobCrawlSourceAccessTests {

    private static final Instant NOW = Instant.parse("2026-11-01T02:00:00Z");

    @Test
    void preventsOverlappingRunsAndOldLeaseCannotReleaseANewerRun() {
        Fixture fixture = new Fixture();
        JobCrawlSourceAccess.Lease first = fixture.access.open("ITVIEC");
        assertEquals(409, assertThrows(ApiException.class, () -> fixture.access.open("ITVIEC")).status());
        first.close();
        try (var second = fixture.access.open("ITVIEC")) {
            first.close();
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.access.open("ITVIEC")).status());
            try (var other = fixture.access.open("TOPCV")) {
                assertNotNull(other);
            }
        }
        try (var third = fixture.access.open("ITVIEC")) {
            assertNotNull(third);
        }
    }

    @Test
    void spacesActualAttemptsAcrossRunsAndSourcesHaveIndependentBudgets() throws Exception {
        Fixture fixture = new Fixture();
        try (var lease = fixture.access.open("ITVIEC")) {
            fixture.access.beforeRequest("ITVIEC");
            fixture.access.beforeRequest("ITVIEC");
            fixture.access.beforeRequest("TOPCV");
        }
        try (var lease = fixture.access.open("ITVIEC")) {
            fixture.access.beforeRequest("ITVIEC");
        }
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(3)), fixture.waits);
        assertEquals(NOW.plusSeconds(6), fixture.clock.instant());
    }

    @Test
    void releaseRetainsCooldownAndDoesNotWaitForItToExpire() throws Exception {
        Fixture fixture = new Fixture();
        Instant until;
        try (var lease = fixture.access.open("ITVIEC")) {
            until = fixture.access.blocked("ITVIEC", null, false);
        }
        assertEquals(NOW.plusSeconds(300), until);
        ApiException failure = assertThrows(ApiException.class, () -> fixture.access.open("ITVIEC"));
        assertEquals(429, failure.status());
        assertTrue(failure.getMessage().contains("Source ITVIEC is cooling down until " + until));
        JobCrawlSourceAccess.CooldownException deferred =
            assertThrows(JobCrawlSourceAccess.CooldownException.class, () -> fixture.access.beforeRequest("ITVIEC"));
        assertEquals(until, deferred.retryAt());
        assertTrue(fixture.waits.isEmpty());
        fixture.access.beforeRequest("TOPCV");
        fixture.clock.advance(Duration.ofMinutes(5));
        try (var lease = fixture.access.open("ITVIEC")) {
            fixture.access.beforeRequest("ITVIEC");
        }
    }

    @Test
    void honorsLongerRetryAfterSecondsAndHttpDatesWithoutShorteningExistingPause() {
        Fixture fixture = new Fixture();
        Instant delta = fixture.access.blocked("ITVIEC", "1800", false);
        assertEquals(NOW.plusSeconds(1800), delta);
        assertEquals(delta, fixture.access.blocked("ITVIEC", "1", false));
        String date =
            ZonedDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(NOW.plusSeconds(3600), fixture.access.blocked("ITVIEC", date, false));
    }

    @Test
    void malformedPastAndShortRetryAfterUseTheMinimumPolicyPause() {
        for (String value : List.of("bad date", "-5", "0", "1", "Sun, 01 Nov 2026 01:00:00 GMT")) {
            Fixture fixture = new Fixture();
            assertEquals(NOW.plusSeconds(300), fixture.access.blocked("ITVIEC", value, false));
        }
        Fixture challenge = new Fixture();
        assertEquals(NOW.plusSeconds(900), challenge.access.blocked("ITVIEC", "10", true));
    }

    @Test
    void enormousValidRetryAfterDoesNotOverflowOrResumeEarly() {
        Fixture fixture = new Fixture();
        assertEquals(Instant.MAX, fixture.access.blocked("ITVIEC", "999999999999999999999999", false));
        assertEquals(Instant.MAX, fixture.access.blocked("ITVIEC", "1", false));
    }

    @Test
    void rechecksAConcurrentSourcePauseAfterPacingWait() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicReference<JobCrawlSourceAccess> holder = new AtomicReference<>();
        JobCrawlSourceAccess access = new JobCrawlSourceAccess(
            Duration.ofSeconds(3),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            clock,
            duration -> {
                clock.advance(duration);
                holder.get().blocked("ITVIEC", null, false);
            }
        );
        holder.set(access);
        access.beforeRequest("ITVIEC");
        assertThrows(JobCrawlSourceAccess.CooldownException.class, () -> access.beforeRequest("ITVIEC"));
    }

    @Test
    void interruptedPacingRestoresInterruptAndDoesNotSendAnotherAttempt() throws Exception {
        MutableClock clock = new MutableClock();
        JobCrawlSourceAccess access = new JobCrawlSourceAccess(
            Duration.ofSeconds(3),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            clock,
            duration -> {
                throw new InterruptedException("test");
            }
        );
        access.beforeRequest("ITVIEC");
        try {
            assertThrows(InterruptedIOException.class, () -> access.beforeRequest("ITVIEC"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsInvalidSourcesAndInvalidDurations() {
        Fixture fixture = new Fixture();
        assertEquals(400, assertThrows(ApiException.class, () -> fixture.access.open("OTHER")).status());
        assertThrows(
            IllegalArgumentException.class,
            () -> new JobCrawlSourceAccess(Duration.ofSeconds(-1), Duration.ofMinutes(5), Duration.ofMinutes(15))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new JobCrawlSourceAccess(Duration.ZERO, Duration.ZERO, Duration.ofMinutes(15))
        );
    }

    private static final class Fixture {

        private final MutableClock clock = new MutableClock();

        private final List<Duration> waits = new ArrayList<>();

        private final JobCrawlSourceAccess access = new JobCrawlSourceAccess(
            Duration.ofSeconds(3),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            clock,
            duration -> {
                waits.add(duration);
                clock.advance(duration);
            }
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(
            ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(
            Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
