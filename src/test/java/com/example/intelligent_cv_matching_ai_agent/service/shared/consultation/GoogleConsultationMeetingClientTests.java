package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.config.shared.consultation.GoogleConsultationProperties;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GoogleConsultationMeetingClientTests {

    private static final String TOKEN = "https://oauth2.googleapis.com/token";

    private static final String SPACES = "https://meet.googleapis.com/v2/spaces";

    private static final String SPACE = "spaces/Meeting_123";

    private static final String MEETING = "https://meet.google.com/abc-defg-hij";

    private static final String SPACE_JSON = "{\"name\":\"" + SPACE + "\",\"meetingUri\":\"" + MEETING + "\"}";

    private static final UUID BOOKING = UUID.fromString("12345678-1234-4234-8234-123456789abc");

    private static final String EVENT_ID = "consultation12345678123442348234123456789abc";

    private static final String EVENTS = "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    private static final String EVENT_LINK = "https://www.google.com/calendar/event?eid=test";

    private static final String EVENT_JSON =
        "{\"id\":\"" + EVENT_ID + "\",\"htmlLink\":\"" + EVENT_LINK + "\",\"location\":\"" + MEETING
            + "\",\"extendedProperties\":{\"private\":{\"consultationBookingId\":\"" + BOOKING + "\"}}}";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GoogleConsultationProperties properties;

    private MockRestServiceServer server;

    private GoogleConsultationMeetingClient client;

    private TestClock clock;

    @BeforeEach
    void setUp() {
        properties = new GoogleConsultationProperties();
        properties.setEnabled(true);
        properties.setClientId("test-client");
        properties.setClientSecret("test-client-secret");
        properties.setRefreshToken("test-refresh-token");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new TestClock();
        client = new GoogleConsultationMeetingClient(properties, builder.build(), clock);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void disabledOrMissingOrganizerFailsClearlyWithoutSendingAnyRequest() {
        properties.setEnabled(false);
        assertFalse(client.configured());
        ApiException disabled = assertThrows(ApiException.class, client::createSpace);
        assertEquals(503, disabled.status());
        assertTrue(disabled.getMessage().contains("GOOGLE_CALENDAR_ENABLED"));
        properties.setEnabled(true);
        properties.setRefreshToken("");
        assertFalse(client.configured());
        ApiException missing = assertThrows(ApiException.class, client::createSpace);
        assertTrue(missing.getMessage().contains("GOOGLE_CALENDAR_REFRESH_TOKEN"));
        assertFalse(missing.getMessage().contains("test-client-secret"));
    }

    @Test
    void rejectsInvalidConfigurationWithoutFailingDisabledApplicationStartup() {
        properties.setMeetingAccessType("PUBLIC");
        assertFalse(client.configured());
        properties.setMeetingAccessType("OPEN");
        properties.setTimeZone("not-a-zone");
        assertFalse(client.configured());
        properties.setTimeZone("Asia/Ho_Chi_Minh");
        properties.setRequestTimeout(Duration.ofSeconds(31));
        assertFalse(client.configured());
        assertDoesNotThrow(() -> new GoogleConsultationMeetingClient(properties));
        properties.setRequestTimeout(Duration.ofSeconds(20));
        assertTrue(client.configured());
    }

    @Test
    void refreshesOAuthOnceAndCreatesInitiallyBlockedSpaceWithoutLoggingSecrets() {
        expectToken("first-token", 3600);
        server.expect(requestTo(SPACES)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer first-token")).andExpect(request -> {
                JsonNode body = body(request);
                assertEquals("OPEN", body.at("/config/accessType").asText());
                assertEquals("CREATOR_APP_ONLY", body.at("/config/entryPointAccess").asText());
            }).andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SPACES + "/Meeting_123")).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer first-token"))
            .andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));
        var space = client.createSpace();
        assertEquals(SPACE, space.name());
        assertEquals(MEETING, space.meetingUri());
        assertEquals(space, client.getSpace(SPACE));
    }

    @Test
    void usesConfiguredRestrictedAccessWithoutChangingInitialEntryBlock() {
        properties.setMeetingAccessType("RESTRICTED");
        expectToken("first-token", 3600);
        server.expect(requestTo(SPACES)).andExpect(request -> {
            JsonNode body = body(request);
            assertEquals("RESTRICTED", body.at("/config/accessType").asText());
            assertEquals("CREATOR_APP_ONLY", body.at("/config/entryPointAccess").asText());
        }).andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));
        client.createSpace();
    }

    @Test
    void opensAndRestrictsOnlyTheEntryPointFieldUsingRestCamelCaseMask() {
        expectToken("first-token", 3600);
        expectEntryChange("ALL");
        expectEntryChange("CREATOR_APP_ONLY");
        server.expect(requestTo(SPACES + "/Meeting_123:endActiveConference")).andExpect(method(HttpMethod.POST))
            .andExpect(content().string("")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.openSpace(SPACE);
        client.restrictSpace(SPACE);
        client.endConference(SPACE);
    }

    @Test
    void doesNotClaimAnEntryChangeWhenGoogleReturnsTheWrongConfiguration() {
        expectToken("first-token", 3600);
        server.expect(requestTo(SPACES + "/Meeting_123?updateMask=config.entryPointAccess")).andRespond(
            withSuccess(
                "{\"name\":\"spaces/Meeting_123\",\"config\":{\"entryPointAccess\":\"ALL\"}}",
                MediaType.APPLICATION_JSON
            )
        );
        assertEquals(502, assertThrows(ApiException.class, () -> client.restrictSpace(SPACE)).status());
    }

    @Test
    void calendarInviteHasBothAttendeesDeterministicIdAndZonedBoundedTimes() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "?sendUpdates=all")).andExpect(method(HttpMethod.POST)).andExpect(request -> {
            JsonNode body = body(request);
            assertEquals(EVENT_ID, body.path("id").asText());
            assertEquals("candidate@example.test", body.at("/attendees/0/email").asText());
            assertEquals("consultant@example.test", body.at("/attendees/1/email").asText());
            assertEquals(2, body.path("attendees").size());
            assertEquals("2026-11-06T10:00:00+07:00", body.at("/start/dateTime").asText());
            assertEquals("2026-11-06T11:00:00+07:00", body.at("/end/dateTime").asText());
            assertEquals("Asia/Ho_Chi_Minh", body.at("/start/timeZone").asText());
            assertEquals("Asia/Ho_Chi_Minh", body.at("/end/timeZone").asText());
            assertEquals(MEETING, body.path("location").asText());
            assertTrue(body.path("description").asText().contains(MEETING));
            assertFalse(body.has("conferenceData"));
            assertFalse(body.path("guestsCanModify").asBoolean());
            assertFalse(body.path("guestsCanInviteOthers").asBoolean());
        }).andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        var event = client.createEvent(BOOKING, booking(), MEETING);
        assertEquals(EVENT_ID, event.id());
        assertEquals(EVENT_LINK, event.htmlLink());
        assertTrue(event.id().matches("[a-v0-9]{5,1024}"));
        assertEquals(event.id(), GoogleConsultationMeetingClient.eventId(BOOKING));
    }

    @Test
    void calendar409RecoversExistingEventWithoutCreatingAnotherInvite() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "?sendUpdates=all")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        assertEquals(EVENT_ID, client.createEvent(BOOKING, booking(), MEETING).id());
    }

    @Test
    void calendar409RejectsAnEventWithAnotherBookingBinding() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "?sendUpdates=all")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess(
                EVENT_JSON.replace(BOOKING.toString(), UUID.randomUUID().toString()),
                MediaType.APPLICATION_JSON
            )
        );
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> client.createEvent(BOOKING, booking(), MEETING)).status()
        );
    }

    @Test
    void calendar409RejectsTheRightBookingIdWithAnUnexpectedMeetingUrl() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "?sendUpdates=all")).andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess(EVENT_JSON.replace(MEETING, "https://meet.google.com/xyz-mnop-abc"), MediaType.APPLICATION_JSON)
        );
        assertEquals(
            502,
            assertThrows(ApiException.class, () -> client.createEvent(BOOKING, booking(), MEETING)).status()
        );
    }

    @Test
    void endingASpaceWithoutAnActiveConferenceAcceptsTheDocumentedEmptyResult() {
        expectToken("first-token", 3600);
        for (int attempt = 0; attempt < 2; attempt++) {
            server.expect(requestTo(SPACES + "/Meeting_123:endActiveConference")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string("")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        }
        client.endConference(SPACE);
        client.endConference(SPACE);
    }

    @Test
    void calendarTombstoneCannotBecomeAReadyMeeting() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess(EVENT_JSON.replace("{\"id\"", "{\"status\":\"cancelled\",\"id\""), MediaType.APPLICATION_JSON)
        );
        assertEquals(502, assertThrows(ApiException.class, () -> client.getEvent(BOOKING)).status());
    }

    @Test
    void calendarCancellationNotifiesAttendeesAndIsIdempotentForMissingOrDeletedEvent() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(EVENT_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(EVENTS + "/" + EVENT_ID + "?sendUpdates=all")).andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
        for (HttpStatus status : new HttpStatus[]{HttpStatus.NOT_FOUND, HttpStatus.GONE}) {
            server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(status));
        }
        client.cancelEvent(BOOKING);
        client.cancelEvent(BOOKING);
        client.cancelEvent(BOOKING);
    }

    @Test
    void cancellationNeverDeletesAnEventBoundToAnotherBooking() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess(
                EVENT_JSON.replace(BOOKING.toString(), UUID.randomUUID().toString()),
                MediaType.APPLICATION_JSON
            )
        );
        assertEquals(502, assertThrows(ApiException.class, () -> client.cancelEvent(BOOKING)).status());
    }

    @Test
    void cancelledCalendarTombstoneIsAlreadyCleanedWithoutAnotherDelete() {
        expectToken("first-token", 3600);
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess("{\"id\":\"" + EVENT_ID + "\",\"status\":\"cancelled\"}", MediaType.APPLICATION_JSON)
        );
        assertDoesNotThrow(() -> client.cancelEvent(BOOKING));
    }

    @Test
    void invalidBookingOrUntrustedJoinUrlCannotTriggerGoogleRequests() {
        Map<String, Object> row = booking();
        row.remove("consultant_email");
        assertEquals(400, assertThrows(ApiException.class, () -> client.createEvent(BOOKING, row, MEETING)).status());
        row.put("consultant_email", "consultant@example.test");
        row.put("ends_at", OffsetDateTime.parse("2026-11-06T04:01:00Z"));
        assertEquals(400, assertThrows(ApiException.class, () -> client.createEvent(BOOKING, row, MEETING)).status());
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> client.createEvent(BOOKING, booking(), "https://meet.google.com.evil.test/abc-defg-hij")
            ).status()
        );
        assertEquals(400, assertThrows(ApiException.class, () -> client.openSpace("spaces/id?fake=1")).status());
    }

    @Test
    void doesNotPublishMalformedMeetingOrWrongBookingEventResponses() {
        expectToken("first-token", 3600);
        server.expect(requestTo(SPACES))
            .andRespond(withSuccess("{\"name\":\"spaces/Meeting_123\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(EVENTS + "/" + EVENT_ID)).andRespond(
            withSuccess("{\"id\":\"wrong\",\"htmlLink\":\"" + EVENT_LINK + "\"}", MediaType.APPLICATION_JSON)
        );
        assertEquals(502, assertThrows(ApiException.class, client::createSpace).status());
        assertEquals(502, assertThrows(ApiException.class, () -> client.getEvent(BOOKING)).status());
    }

    @Test
    void expiredCachedTokenRefreshesAnd401RetriesAtMostOnce() {
        expectToken("first-token", 120);
        server.expect(requestTo(SPACES)).andRespond(withSuccess(SPACE_JSON, MediaType.APPLICATION_JSON));
        expectToken("second-token", 3600);
        server.expect(requestTo(SPACES)).andExpect(header("Authorization", "Bearer second-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("third-token", 3600);
        server.expect(requestTo(SPACES)).andExpect(header("Authorization", "Bearer third-token"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-rejected-token"));
        client.createSpace();
        clock.advance(Duration.ofSeconds(110));
        ApiException error = assertThrows(ApiException.class, client::createSpace);
        assertEquals(503, error.status());
        assertFalse(error.getMessage().contains("secret-rejected-token"));
        assertNull(error.getCause());
    }

    @Test
    void oauthAndProviderErrorsDoNotExposeResponseBodyOrCredentials() {
        server.expect(requestTo(TOKEN)).andRespond(
            withStatus(HttpStatus.BAD_REQUEST).body("test-client-secret test-refresh-token provider-private-data")
        );
        ApiException error = assertThrows(ApiException.class, client::createSpace);
        assertEquals(503, error.status());
        assertTrue(error.getMessage().contains("reauthor") || error.getMessage().contains("consent"));
        assertFalse(error.getMessage().contains("test-client-secret"));
        assertFalse(error.getMessage().contains("test-refresh-token"));
        assertFalse(error.getMessage().contains("provider-private-data"));
        assertNull(error.getCause());
    }

    @Test
    void malformedOAuthTokenCannotReachAnAuthorizationHeader() {
        server.expect(requestTo(TOKEN))
            .andRespond(
                withSuccess(
                    ("{\"access_token\":\"private token with spaces\","
                        + "\"token_type\":\"Bearer\",\"expires_in\":3600}"),
                    MediaType.APPLICATION_JSON
                )
            );
        ApiException error = assertThrows(ApiException.class, client::createSpace);
        assertEquals(502, error.status());
        assertFalse(error.getMessage().contains("private token"));
        assertNull(error.getCause());
    }

    @Test
    void insufficientScopesAndTimeoutRemainActionableWithoutRawProviderErrors() {
        expectToken("first-token", 3600);
        server.expect(requestTo(SPACES)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private oauth details"));
        server.expect(requestTo(SPACES)).andRespond(request -> {
            throw new HttpTimeoutException("private timeout details");
        });
        ApiException forbidden = assertThrows(ApiException.class, client::createSpace);
        assertEquals(503, forbidden.status());
        assertTrue(forbidden.getMessage().contains("both scopes"));
        assertFalse(forbidden.getMessage().contains("private"));
        ApiException timeout = assertThrows(ApiException.class, client::createSpace);
        assertEquals(504, timeout.status());
        assertNull(timeout.getCause());
    }

    private void expectToken(
        String token,
        int expiry) {
        server.expect(requestTo(TOKEN)).andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(
                content().string(
                    ("client_id=test-client&client_secret=test-client-secret&refresh_token=t"
                        + "est-refresh-token&grant_type=refresh_token")
                )
            )
            .andRespond(
                withSuccess(
                    "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiry + "}",
                    MediaType.APPLICATION_JSON
                )
            );
    }

    private void expectEntryChange(
        String value) {
        server.expect(requestTo(SPACES + "/Meeting_123?updateMask=config.entryPointAccess"))
            .andExpect(method(HttpMethod.PATCH)).andExpect(request -> {
                JsonNode body = body(request);
                assertEquals(SPACE, body.path("name").asText());
                assertEquals(value, body.at("/config/entryPointAccess").asText());
                assertEquals(1, body.path("config").size());
            }).andRespond(
                withSuccess(
                    "{\"name\":\"" + SPACE + "\",\"config\":{\"entryPointAccess\":\"" + value + "\"}}",
                    MediaType.APPLICATION_JSON
                )
            );
    }

    private static JsonNode body(
        org.springframework.http.client.ClientHttpRequest request) {
        return JSON.readTree(((MockClientHttpRequest) request).getBodyAsString());
    }

    private static Map<String, Object> booking() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", "CV and career guidance");
        row.put("candidate_email", "candidate@example.test");
        row.put("consultant_email", "consultant@example.test");
        row.put("starts_at", OffsetDateTime.parse("2026-11-06T03:00:00Z"));
        row.put("ends_at", OffsetDateTime.parse("2026-11-06T04:00:00Z"));
        return row;
    }

    private static final class TestClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-06T00:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(
            ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        private void advance(
            Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }
    }
}
