package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@Testcontainers(disabledWithoutDocker = true)
class SecurityHttpIntegrationTests {

    private static final String TEMPLATES = "/api/v1/admin/resume-templates";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final List<UUID> users = new ArrayList<>();

    private final List<UUID> templates = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @AfterEach
    void removeTestIdentities() {
        for (UUID id : templates) {
            db.update("DELETE FROM resume_templates WHERE id=?", id);
        }
        for (UUID id : users) {
            db.update("DELETE FROM admin_actions WHERE admin_user_id=?", id);
            db.update("DELETE FROM audit_logs WHERE actor_user_id=?", id);
            db.update("DELETE FROM refresh_tokens WHERE user_id=?", id);
            db.update("DELETE FROM users WHERE id=?", id);
        }
    }

    @Test
    void managerCanUseOperationalScreensButCannotEscalateToAdministrator() throws Exception {
        String manager = token("MANAGER");
        for (String path : List.of(
            "/api/v1/admin/dashboard",
            "/api/v1/admin/rag/documents",
            "/api/v1/admin/rag/courses",
            "/api/v1/admin/rag/resources",
            TEMPLATES,
            "/api/v1/admin/subscription-tiers",
            "/api/v1/admin/consultation-services",
            "/api/v1/admin/payment-reports",
            "/api/v1/admin/jobs",
            "/api/v1/admin/crawl-settings",
            "/api/admin/crawl-settings",
            "/api/v1/admin/announcements",
            "/api/v1/admin/monitoring/billing",
            "/api/v1/admin/monitoring/feedback",
            "/api/v1/support/tickets",
            "/api/v1/consultation-orders"
        )) {
            HttpResponse<String> response = send("GET", path, manager);
            assertEquals(200, response.statusCode(), path + ": " + response.body());
        }
        for (String path : List.of(
            "/api/v1/admin/users",
            "/api/v1/admin/ai/configurations",
            "/api/v1/admin/ai/runtime",
            "/api/v1/admin/ai/prompts",
            "/api/v1/admin/monitoring/logs",
            "/api/v1/admin/monitoring/audit"
        )) {
            assertEquals(403, send("GET", path, manager).statusCode(), path);
        }
        assertEquals(403, send("POST", "/api/v1/admin/users", manager).statusCode());
        assertEquals(403, send("POST", "/api/v1/admin/ai/configurations", manager).statusCode());
    }

    @Test
    void managerCanPersistTemplateChangesWithAnAuditTrail() throws Exception {
        String body = """
            {"name":"Manager Template","language":"vi","active":true,"trialEnabled":true,
            "htmlContent":"<h1 data-cv-field=\\\"fullName\\\"></h1><section data-cv-section=\\\"summary\\\"></section>"}
            """;
        HttpResponse<String> response = send("POST", TEMPLATES, token("MANAGER"), body);
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> created = JsonMapper.builder().build().readValue(response.body(), Map.class);
        UUID id = UUID.fromString(created.get("id").toString());
        templates.add(id);
        assertEquals("Manager Template", db.scalar("SELECT name FROM resume_templates WHERE id=?", id));
        assertTrue(((Number) db.scalar("SELECT count(*) FROM audit_logs WHERE entity_id=?", id)).intValue() > 0);
    }

    @Test
    void realHttpRequestsAuthenticateAdminAndRejectCandidateAndMissingTokens() throws Exception {
        assertEquals(200, send("GET", TEMPLATES, token("ADMIN")).statusCode());
        assertEquals(403, send("GET", TEMPLATES, token("CANDIDATE")).statusCode());
        HttpResponse<String> missing = send("GET", TEMPLATES, null);
        assertEquals(401, missing.statusCode());
        assertTrue(missing.body().contains("Authentication required"));
        HttpResponse<String> invalid = send("GET", TEMPLATES, "Bearer invalid-test-token");
        assertEquals(401, invalid.statusCode());
        assertTrue(invalid.body().contains("Invalid or expired session"));
    }

    @Test
    void administratorCanCreateTemplateOverRealHttp() throws Exception {
        String body = """
            {
              "name":"HTTP Admin Template",
              "language":"vi",
              "active":true,
              "trialEnabled":true,
              "htmlContent":"<h1 data-cv-field=\\\"fullName\\\"></h1><section data-cv-section=\\\"summary\\\"></section>"
            }
            """;
        HttpResponse<String> response = send("POST", TEMPLATES, token("ADMIN"), body);
        assertEquals(200, response.statusCode(), response.body());
        Map<?, ?> created = JsonMapper.builder().build().readValue(response.body(), Map.class);
        UUID id = UUID.fromString(created.get("id").toString());
        templates.add(id);
        assertEquals("HTTP Admin Template", db.scalar("SELECT name FROM resume_templates WHERE id=?", id));
    }

    @Test
    void repeatedApiPrefixWithValidTokenReturnsNotFoundInsteadOfAuthenticationFailure() throws Exception {
        Object before = db.scalar("SELECT count(*) FROM resume_templates");
        HttpResponse<String> response = send("POST", "/api/v1" + TEMPLATES, token("ADMIN"));
        assertEquals(404, response.statusCode(), response.body());
        assertEquals(before, db.scalar("SELECT count(*) FROM resume_templates"));
    }

    @Test
    void unsupportedMethodWithValidTokenReturnsMethodNotAllowedInsteadOfAuthenticationFailure() throws Exception {
        HttpResponse<String> response = send("PUT", TEMPLATES, token("ADMIN"));
        assertEquals(405, response.statusCode(), response.body());
    }

    @Test
    void errorPathIsStillProtectedForDirectRequests() throws Exception {
        assertEquals(401, send("GET", "/error", null).statusCode());
    }

    @Test
    void browserPreflightAllowsConfiguredFrontendAndGuestHeadersWithoutAuthentication() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + TEMPLATES + "/import"))
            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type,x-guest-session")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));
        String headers = response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase();
        assertTrue(headers.contains("authorization"));
        assertTrue(headers.contains("x-guest-session"));
    }

    @Test
    void corsHeadersArePresentOnPublicDataAndAuthenticationErrors() throws Exception {
        for (String path : List.of("/api/v1/jobs?limit=1", "/api/v1/me")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:5173").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(path.contains("jobs") ? 200 : 401, response.statusCode(), response.body());
            assertEquals(
                "http://localhost:5173",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse("")
            );
        }
    }

    @Test
    void corsRejectsUnconfiguredOriginsAndHeaders() throws Exception {
        HttpRequest unknownOrigin = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs"))
            .header("Origin", "https://untrusted.example.test").GET().build();
        HttpResponse<String> denied = client.send(unknownOrigin, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        HttpRequest unknownHeader = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/jobs"))
            .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "x-not-supported")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, client.send(unknownHeader, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private String token(
        String role) {
        UUID id = db.insert(
            "users",
            Map.of(
                "email",
                "http-security-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "HTTP Security Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
        users.add(id);
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String authorization)
        throws Exception {
        return send(method, path, authorization, "{}");
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String authorization,
        String body)
        throws Exception {
        HttpRequest.Builder request =
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
