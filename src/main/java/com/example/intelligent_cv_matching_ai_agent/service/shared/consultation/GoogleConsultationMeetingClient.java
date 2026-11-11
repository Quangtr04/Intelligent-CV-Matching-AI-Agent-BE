package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.config.shared.consultation.GoogleConsultationProperties;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Bounded Google requests only; the booking worker owns persistence, retries and the meeting lifecycle. */
@Service
public class GoogleConsultationMeetingClient {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private static final String MEET_URL = "https://meet.googleapis.com/v2";

    private static final String CALENDAR_URL = "https://www.googleapis.com/calendar/v3";

    private final GoogleConsultationProperties properties;

    private final RestClient client;

    private final Clock clock;

    private String cachedToken;

    private Instant tokenValidUntil = Instant.EPOCH;

    public record MeetingSpace(
        String name,
        String meetingUri
    ) {
    }

    public record CalendarEvent(
        String id,
        String htmlLink
    ) {
    }

    @Autowired
    public GoogleConsultationMeetingClient(
        GoogleConsultationProperties properties) {
        this(properties, httpClient(properties), Clock.systemUTC());
    }

    GoogleConsultationMeetingClient(
        GoogleConsultationProperties properties,
        RestClient client,
        Clock clock) {
        this.properties = properties;
        this.client = client;
        this.clock = clock;
    }

    public boolean configured() {
        return configurationProblem() == null;
    }

    public MeetingSpace createSpace() {
        requireConfigured();
        Map<String, Object> body = Map.of(
            "config",
            Map.of("accessType", properties.getMeetingAccessType(), "entryPointAccess", "CREATOR_APP_ONLY")
        );
        return space(request(HttpMethod.POST, URI.create(MEET_URL + "/spaces"), body), null);
    }

    public MeetingSpace getSpace(
        String name) {
        requireConfigured();
        return space(request(HttpMethod.GET, spaceUri(name, ""), null), name);
    }

    public void openSpace(
        String name) {
        changeEntryPoint(name, "ALL");
    }

    public void restrictSpace(
        String name) {
        changeEntryPoint(name, "CREATOR_APP_ONLY");
    }

    private void changeEntryPoint(
        String name,
        String entryPointAccess) {
        requireConfigured();
        URI endpoint = UriComponentsBuilder.fromUri(spaceUri(name, ""))
            .queryParam("updateMask", "config.entryPointAccess").build().encode().toUri();
        Map<?, ?> response = request(
            HttpMethod.PATCH,
            endpoint,
            Map.of("name", name, "config", Map.of("entryPointAccess", entryPointAccess))
        );
        if (response == null || !name.equals(response.get("name"))
            || !(response.get("config") instanceof Map<?, ?> config)
            || !entryPointAccess.equals(config.get("entryPointAccess"))) {
            throw invalidResponse("Google Meet did not confirm the requested meeting access setting");
        }
    }

    public void endConference(
        String name) {
        requireConfigured();
        request(HttpMethod.POST, spaceUri(name, ":endActiveConference"), null);
    }

    public CalendarEvent createEvent(
        UUID bookingId,
        Map<String, Object> booking,
        String meetingUrl) {
        requireConfigured();
        Map<String, Object> body = eventBody(bookingId, booking, meetingUrl);
        try {
            return event(request(HttpMethod.POST, eventUri(null, true), body), bookingId, meetingUrl);
        } catch (GoogleRequestFailure failure) {
            if (failure.providerStatus() == 409) {
                return readEvent(bookingId, meetingUrl);
            }
            throw failure;
        }
    }

    public CalendarEvent getEvent(
        UUID bookingId) {
        requireConfigured();
        return readEvent(bookingId, null);
    }

    private CalendarEvent readEvent(
        UUID bookingId,
        String expectedMeetingUrl) {
        String id = eventId(bookingId);
        return event(request(HttpMethod.GET, eventUri(id, false), null), bookingId, expectedMeetingUrl);
    }

    public void cancelEvent(
        UUID bookingId) {
        requireConfigured();
        String id = eventId(bookingId);
        try {
            Map<?, ?> existing = request(HttpMethod.GET, eventUri(id, false), null);
            if (existing != null && id.equals(existing.get("id")) && "cancelled".equals(existing.get("status"))) {
                return;
            }
            event(existing, bookingId, null);
            request(HttpMethod.DELETE, eventUri(id, true), null);
        } catch (GoogleRequestFailure failure) {
            if (failure.providerStatus() != 404 && failure.providerStatus() != 410) {
                throw failure;
            }
        }
    }

    public static String eventId(
        UUID bookingId) {
        if (bookingId == null) {
            throw ApiException.badRequest("A booking ID is required for the Google Calendar event");
        }
        return "consultation" + bookingId.toString().replace("-", "");
    }

    private Map<String, Object> eventBody(
        UUID id,
        Map<String, Object> booking,
        String meetingUrl) {
        if (booking == null) {
            throw ApiException.badRequest("Booking details are required for the Google Calendar event");
        }
        requireMeetingUrl(meetingUrl);
        String candidate = email(booking.get("candidate_email"), "candidate");
        String consultant = email(booking.get("consultant_email"), "consultant");
        OffsetDateTime starts = bookingTime(booking.get("starts_at"));
        OffsetDateTime ends = bookingTime(booking.get("ends_at"));
        Duration duration = Duration.between(starts.toInstant(), ends.toInstant());
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofMinutes(60)) > 0) {
            throw ApiException.badRequest("Google consultation must end after its start and last at most 60 minutes");
        }
        ZoneId zone = ZoneId.of(properties.getTimeZone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId(id));
        body.put("summary", "Career consultation: " + plain(booking.get("subject"), 255));
        body.put(
            "description",
            "Booking: " + id + "\nGoogle Meet: " + meetingUrl
                + ("\nJoin during the accepted appointment. This meeting ends at its " + "scheduled end time.")
        );
        body.put("location", meetingUrl);
        body.put(
            "start",
            Map.of(
                "dateTime",
                starts.atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timeZone",
                zone.getId()
            )
        );
        body.put(
            "end",
            Map.of(
                "dateTime",
                ends.atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "timeZone",
                zone.getId()
            )
        );
        body.put("attendees", List.of(Map.of("email", candidate), Map.of("email", consultant)));
        body.put("visibility", "private");
        body.put("guestsCanModify", false);
        body.put("guestsCanInviteOthers", false);
        body.put("extendedProperties", Map.of("private", Map.of("consultationBookingId", id.toString())));
        return body;
    }

    private Map<?, ?> request(
        HttpMethod method,
        URI endpoint,
        Object body) {
        String token = token();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String bearer = token;
                RestClient.RequestBodySpec request =
                    client.method(method).uri(endpoint).headers(headers -> headers.setBearerAuth(bearer));
                if (body != null) {
                    request.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                return request.retrieve().body(Map.class);
            } catch (RestClientResponseException failure) {
                if (failure.getStatusCode().value() == 401 && attempt == 0) {
                    token = refreshRejectedToken(token);
                } else {
                    throw providerFailure(failure.getStatusCode().value());
                }
            } catch (ResourceAccessException failure) {
                throw connectionFailure(failure);
            } catch (RestClientException failure) {
                throw invalidResponse("Google returned an unreadable consultation response");
            }
        }
        throw providerFailure(401);
    }

    private synchronized String token() {
        if (cachedToken != null && clock.instant().isBefore(tokenValidUntil)) {
            return cachedToken;
        }
        requireConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("refresh_token", properties.getRefreshToken());
        form.add("grant_type", "refresh_token");
        try {
            Map<?, ?> response = client.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String access) || access.isBlank()
                || access.chars().anyMatch(character -> character <= 32 || character >= 127)
                || !(response.get("expires_in") instanceof Number seconds) || seconds.longValue() < 1
                || seconds.longValue() > 86_400
                || !"Bearer".equalsIgnoreCase(String.valueOf(response.get("token_type")))) {
                throw invalidResponse("Google OAuth did not return a valid bearer token and expiry");
            }
            long lifetime = seconds.longValue();
            cachedToken = access;
            tokenValidUntil = clock.instant().plusSeconds(lifetime - Math.min(60, lifetime / 10));
            return cachedToken;
        } catch (RestClientResponseException failure) {
            throw new ApiException(
                503,
                ("Google OAuth refresh failed. Verify organizer credentials, granted "
                    + "scopes and refresh-token consent.")
            );
        } catch (ResourceAccessException failure) {
            throw connectionFailure(failure);
        } catch (RestClientException failure) {
            throw invalidResponse("Google OAuth returned an unreadable token response");
        }
    }

    private synchronized String refreshRejectedToken(
        String rejected) {
        if (rejected.equals(cachedToken)) {
            cachedToken = null;
            tokenValidUntil = Instant.EPOCH;
        }
        return token();
    }

    private void requireConfigured() {
        String problem = configurationProblem();
        if (problem != null) {
            throw new ApiException(503, problem);
        }
    }

    private String configurationProblem() {
        if (!properties.isEnabled()) {
            return ("Google consultation meetings are disabled. Set "
                + "GOOGLE_CALENDAR_ENABLED=true and authorize an organizer.");
        }
        List<String> missing = new ArrayList<>();
        if (blank(properties.getClientId())) {
            missing.add("GOOGLE_CALENDAR_CLIENT_ID");
        }
        if (blank(properties.getClientSecret())) {
            missing.add("GOOGLE_CALENDAR_CLIENT_SECRET");
        }
        if (blank(properties.getRefreshToken())) {
            missing.add("GOOGLE_CALENDAR_REFRESH_TOKEN");
        }
        if (!missing.isEmpty()) {
            return "Google consultation organizer configuration is missing: " + String.join(", ", missing);
        }
        if (blank(properties.getCalendarId())) {
            return "Set GOOGLE_CALENDAR_ID to the organizer's writable calendar (usually primary).";
        }
        if (blank(properties.getMeetingAccessType())
            || !Set.of("OPEN", "TRUSTED", "RESTRICTED").contains(properties.getMeetingAccessType())) {
            return "GOOGLE_MEET_ACCESS_TYPE must be OPEN, TRUSTED or RESTRICTED.";
        }
        try {
            ZoneId.of(properties.getTimeZone());
        } catch (RuntimeException failure) {
            return "GOOGLE_CALENDAR_TIME_ZONE must be a valid IANA time zone such as Asia/Ho_Chi_Minh.";
        }
        if (!validTimeout(properties.getRequestTimeout())) {
            return "GOOGLE_CALENDAR_REQUEST_TIMEOUT must be positive and at most PT30S.";
        }
        return null;
    }

    private URI eventUri(
        String eventId,
        boolean notify) {
        var builder = UriComponentsBuilder.fromUriString(CALENDAR_URL)
            .pathSegment("calendars", properties.getCalendarId(), "events");
        if (eventId != null) {
            builder.pathSegment(eventId);
        }
        if (notify) {
            builder.queryParam("sendUpdates", "all");
        }
        return builder.build().encode().toUri();
    }

    private static URI spaceUri(
        String name,
        String suffix) {
        if (name == null || !name.matches("spaces/[A-Za-z0-9_-]{1,128}")) {
            throw ApiException.badRequest("A valid Google Meet space resource name is required");
        }
        return URI.create(MEET_URL + "/" + name + suffix);
    }

    private static MeetingSpace space(
        Map<?, ?> response,
        String expected) {
        if (response == null || !(response.get("name") instanceof String name)
            || !name.matches("spaces/[A-Za-z0-9_-]{1,128}") || expected != null && !expected.equals(name)
            || !(response.get("meetingUri") instanceof String uri) || !validMeetingUrl(uri)) {
            throw invalidResponse("Google Meet did not return a valid meeting space and join URL");
        }
        return new MeetingSpace(name, uri);
    }

    private static CalendarEvent event(
        Map<?, ?> response,
        UUID bookingId,
        String expectedMeetingUrl) {
        String expected = eventId(bookingId);
        if (response == null || !expected.equals(response.get("id")) || "cancelled".equals(response.get("status"))
            || !(response.get("htmlLink") instanceof String link) || !validCalendarUrl(link)
            || !(response.get("location") instanceof String location) || !validMeetingUrl(location)
            || expectedMeetingUrl != null && !expectedMeetingUrl.equals(location)
            || !(response.get("extendedProperties") instanceof Map<?, ?> extended)
            || !(extended.get("private") instanceof Map<?, ?> privateProperties)
            || !bookingId.toString().equals(privateProperties.get("consultationBookingId"))) {
            throw invalidResponse("Google Calendar did not return the expected active consultation event");
        }
        return new CalendarEvent(expected, link);
    }

    private static boolean validCalendarUrl(
        String link) {
        try {
            URI uri = URI.create(link);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && Set.of("www.google.com", "calendar.google.com").contains(uri.getHost())
                && uri.getPath().startsWith("/calendar/");
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void requireMeetingUrl(
        String uri) {
        if (!validMeetingUrl(uri)) {
            throw ApiException.badRequest("A valid HTTPS Google Meet join URL is required");
        }
    }

    private static boolean validMeetingUrl(
        String uri) {
        return uri != null && uri.matches("https://meet\\.google\\.com/[a-z]+-[a-z]+-[a-z]+");
    }

    private static String email(
        Object value,
        String participant) {
        if (!(value instanceof String address) || address.length() > 320
            || !address.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw ApiException.badRequest("A valid " + participant + " email is required for the calendar invitation");
        }
        return address;
    }

    private static OffsetDateTime bookingTime(
        Object value) {
        try {
            if (value instanceof OffsetDateTime dateTime) {
                return dateTime;
            }
            if (value instanceof Instant instant) {
                return instant.atOffset(java.time.ZoneOffset.UTC);
            }
            if (value instanceof Timestamp timestamp) {
                return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
            }
            if (value instanceof ZonedDateTime zoned) {
                return zoned.toOffsetDateTime();
            }
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException failure) {
            throw ApiException.badRequest("Valid booking start and end times with UTC offsets are required");
        }
    }

    private static String plain(
        Object value,
        int limit) {
        String text = value == null ? "Career guidance" : value.toString().replaceAll("[\\p{Cntrl}]", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }

    private static boolean blank(
        String value) {
        return value == null || value.isBlank();
    }

    private static boolean validTimeout(
        Duration timeout) {
        return timeout != null && !timeout.isNegative() && !timeout.isZero()
            && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    private static RestClient httpClient(
        GoogleConsultationProperties properties) {
        Duration timeout =
            validTimeout(properties.getRequestTimeout()) ? properties.getRequestTimeout() : Duration.ofSeconds(20);
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private static GoogleRequestFailure providerFailure(
        int status) {
        String message = status == 401 || status == 403
            ? ("Google denied consultation access. Enable Meet/Calendar APIs and "
                + "reauthorize the organizer with both scopes.")
            : status == 429
                ? "Google consultation request rate limit was reached; retry later."
                : "Google could not complete the consultation request (HTTP " + status + ").";
        return new GoogleRequestFailure(status == 401 || status == 403 || status == 429 ? 503 : 502, message, status);
    }

    private static ApiException connectionFailure(
        ResourceAccessException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return new ApiException(
                    504,
                    "Google consultation request timed out; check provisioning status and retry."
                );
            }
            cause = cause.getCause();
        }
        return new ApiException(503, "Google consultation service is unavailable; check connectivity and retry.");
    }

    private static ApiException invalidResponse(
        String message) {
        return new ApiException(502, message);
    }

    private static final class GoogleRequestFailure extends ApiException {

        private final int providerStatus;

        private GoogleRequestFailure(
            int status,
            String message,
            int providerStatus) {
            super(status, message);
            this.providerStatus = providerStatus;
        }

        private int providerStatus() {
            return providerStatus;
        }
    }
}
