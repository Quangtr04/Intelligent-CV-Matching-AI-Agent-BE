package com.example.intelligent_cv_matching_ai_agent.config.shared;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationEnvironmentTest {

    private static final List<String> CONFIG_FILES =
        List.of("application.yaml", "application-dev.yaml", "application-prod.yaml", "application-docker.yaml");

    private static final String ENV_IMPORT =
        "optional:file:${APP_ENV_FILE:.env}[extension=.properties][encoding=utf-8]";

    private static final String FIXTURE_ENV = """
        REDIS_HOST=fixture.redis.invalid
        REDIS_PORT=6387
        REDIS_USERNAME=
        REDIS_PASSWORD=
        REDIS_SSL_ENABLED=true
        AI_ENABLED=true
        GOOGLE_CLIENT_ID=
        MAIL_FROM=file@example.invalid
        JOB_COLLECTION_ITVIEC_LOCATION=Hồ Chí Minh
        """;

    @TempDir
    Path directory;

    @Test
    void nativeConfigImportResolvesStringsNumbersBooleansEmptyValuesAndUtf8() throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of(), Map.of());
        assertEquals("fixture.redis.invalid", environment.getRequiredProperty("spring.data.redis.host"));
        assertEquals(6387, environment.getRequiredProperty("spring.data.redis.port", Integer.class));
        assertTrue(environment.getRequiredProperty("spring.data.redis.ssl.enabled", Boolean.class));
        assertTrue(environment.getRequiredProperty("app.ai.enabled", Boolean.class));
        assertEquals("", environment.getRequiredProperty("spring.data.redis.username"));
        assertEquals("", environment.getRequiredProperty("spring.data.redis.password"));
        assertEquals("", environment.getRequiredProperty("app.auth.google-client-id"));
        assertEquals("file@example.invalid", environment.getRequiredProperty("app.mail.from"));
        assertEquals("Hồ Chí Minh", environment.getRequiredProperty("app.jobs.collection.sources[0].location"));
    }

    @Test
    void processEnvironmentOverridesImportedFileValues() throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of("MAIL_FROM", "process@example.invalid"), Map.of());
        assertEquals("process@example.invalid", environment.getRequiredProperty("app.mail.from"));
        assertEquals("process@example.invalid", environment.getRequiredProperty("app.auth.mail-from"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "docker"})
    void tokenLifetimesLoadFromFileAndRespectProcessOverridesAcrossProfiles(
        String profile)
        throws IOException {
        String fixture = """
            APP_ACCESS_TOKEN_TTL_SECONDS=123
            APP_REFRESH_TOKEN_TTL_SECONDS=45678
            """;
        StandardEnvironment fileEnvironment =
            loadEnvironment(fixture, Map.of(), Map.of(), "--spring.profiles.active=" + profile);
        assertEquals(123, fileEnvironment.getRequiredProperty("app.security.access-token-ttl-seconds", Integer.class));
        assertEquals(
            45678,
            fileEnvironment.getRequiredProperty("app.security.refresh-token-ttl-seconds", Integer.class)
        );

        StandardEnvironment processEnvironment = loadEnvironment(
            fixture,
            Map.of("APP_ACCESS_TOKEN_TTL_SECONDS", "300", "APP_REFRESH_TOKEN_TTL_SECONDS", "86400"),
            Map.of(),
            "--spring.profiles.active=" + profile
        );
        assertEquals(
            300,
            processEnvironment.getRequiredProperty("app.security.access-token-ttl-seconds", Integer.class)
        );
        assertEquals(
            86400,
            processEnvironment.getRequiredProperty("app.security.refresh-token-ttl-seconds", Integer.class)
        );
    }

    @Test
    void systemPropertiesOverrideProcessEnvironmentAndImportedFileValues() throws IOException {
        StandardEnvironment environment = loadEnvironment(
            Map.of("MAIL_FROM", "process@example.invalid"),
            Map.of("MAIL_FROM", "system@example.invalid")
        );
        assertEquals("system@example.invalid", environment.getRequiredProperty("app.mail.from"));
    }

    @Test
    void commandLineOverridesSystemPropertiesProcessEnvironmentAndImportedFileValues() throws IOException {
        StandardEnvironment environment = loadEnvironment(
            Map.of("MAIL_FROM", "process@example.invalid"),
            Map.of("MAIL_FROM", "system@example.invalid"),
            "--MAIL_FROM=cli@example.invalid",
            "--spring.data.redis.port=6391"
        );
        assertEquals("cli@example.invalid", environment.getRequiredProperty("app.mail.from"));
        assertEquals(6391, environment.getRequiredProperty("spring.data.redis.port", Integer.class));
    }

    @Test
    void defaultDevProfileUsesLocalServicesAndDevelopmentDefaults() throws IOException {
        StandardEnvironment environment = loadEnvironment("", Map.of(), Map.of());
        assertArrayEquals(new String[]{"dev"}, environment.getDefaultProfiles());
        assertEquals(
            "jdbc:postgresql://localhost:5432/edunexus",
            environment.getRequiredProperty("spring.datasource.url")
        );
        assertEquals("localhost", environment.getRequiredProperty("spring.data.redis.host"));
        assertEquals(6379, environment.getRequiredProperty("spring.data.redis.port", Integer.class));
        assertEquals("./uploads", environment.getRequiredProperty("app.storage.path"));
        assertEquals("DEBUG", environment.getRequiredProperty("logging.level.web"));
        assertEquals("INFO", environment.getRequiredProperty("logging.level.sql"));
        assertEquals("DEBUG", environment.getRequiredProperty("logging.level.org.springframework.web"));
        assertEquals("DEBUG", environment.getRequiredProperty("logging.level.org.springframework.jdbc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "docker"})
    void explicitProfilesPreserveImportedConnectionAndStorageOverrides(
        String profile)
        throws IOException {
        String fixture = """
            DATABASE_URL=jdbc:postgresql://external-db.invalid:5542/custom_database
            REDIS_HOST=external-redis.invalid
            REDIS_PORT=6388
            UPLOAD_DIR=./custom-uploads
            LOG_WEB_LEVEL=WARN
            """;
        StandardEnvironment environment =
            loadEnvironment(fixture, Map.of(), Map.of(), "--spring.profiles.active=" + profile);
        assertArrayEquals(new String[]{profile}, environment.getActiveProfiles());
        assertEquals(
            "jdbc:postgresql://external-db.invalid:5542/custom_database",
            environment.getRequiredProperty("spring.datasource.url")
        );
        assertEquals("external-redis.invalid", environment.getRequiredProperty("spring.data.redis.host"));
        assertEquals(6388, environment.getRequiredProperty("spring.data.redis.port", Integer.class));
        assertEquals("./custom-uploads", environment.getRequiredProperty("app.storage.path"));
        assertEquals("WARN", environment.getRequiredProperty("logging.level.web"));
    }

    @Test
    void processEnvironmentCanSelectDockerProfileAndOverrideFileProfile() throws IOException {
        StandardEnvironment environment =
            loadEnvironment("SPRING_PROFILES_ACTIVE=dev\n", Map.of("SPRING_PROFILES_ACTIVE", "docker"), Map.of());
        assertArrayEquals(new String[]{"docker"}, environment.getActiveProfiles());
        assertEquals(
            "jdbc:postgresql://postgres:5432/edunexus",
            environment.getRequiredProperty("spring.datasource.url")
        );
        assertEquals("redis", environment.getRequiredProperty("spring.data.redis.host"));
    }

    @Test
    void importedFileCanSelectDockerProfile() throws IOException {
        StandardEnvironment environment = loadEnvironment("SPRING_PROFILES_ACTIVE=docker\n", Map.of(), Map.of());
        assertArrayEquals(new String[]{"docker"}, environment.getActiveProfiles());
        assertEquals("redis", environment.getRequiredProperty("spring.data.redis.host"));
        assertEquals("/app/uploads", environment.getRequiredProperty("app.storage.path"));
    }

    @Test
    void commandLineProfileOverridesProcessAndImportedFileProfiles() throws IOException {
        StandardEnvironment environment = loadEnvironment(
            "SPRING_PROFILES_ACTIVE=prod\n",
            Map.of("SPRING_PROFILES_ACTIVE", "dev"),
            Map.of(),
            "--spring.profiles.active=docker"
        );
        assertArrayEquals(new String[]{"docker"}, environment.getActiveProfiles());
        assertEquals("redis", environment.getRequiredProperty("spring.data.redis.host"));
    }

    @Test
    void devDatabaseDefaultsRespectPostgresDatabaseAndPortSettings() throws IOException {
        StandardEnvironment environment = loadEnvironment(
            "POSTGRES_PORT=5545\nPOSTGRES_DB=custom_dev\n",
            Map.of(),
            Map.of(),
            "--spring.profiles.active=dev"
        );
        assertEquals(
            "jdbc:postgresql://localhost:5545/custom_dev",
            environment.getRequiredProperty("spring.datasource.url")
        );
    }

    @Test
    void dockerProfileUsesServicePortsAndContainerStorageWithoutLocalSettings() throws IOException {
        StandardEnvironment environment = loadEnvironment(
            "POSTGRES_PORT=5545\nPOSTGRES_DB=custom_docker\n",
            Map.of(),
            Map.of(),
            "--spring.profiles.active=docker"
        );
        assertEquals(
            "jdbc:postgresql://postgres:5432/custom_docker",
            environment.getRequiredProperty("spring.datasource.url")
        );
        assertEquals("redis", environment.getRequiredProperty("spring.data.redis.host"));
        assertEquals(6379, environment.getRequiredProperty("spring.data.redis.port", Integer.class));
        assertEquals("/app/uploads", environment.getRequiredProperty("app.storage.path"));
        assertFalse(environment.getRequiredProperty("spring.docker.compose.enabled", Boolean.class));
        assertFalse(environment.getRequiredProperty("app.auth.expose-development-code", Boolean.class));
        assertEquals("INFO", environment.getRequiredProperty("logging.level.web"));
        assertEquals("never", environment.getRequiredProperty("server.error.include-message"));
        assertEquals("never", environment.getRequiredProperty("server.error.include-stacktrace"));
    }

    @Test
    void productionRequiresExternalConnectionsAndBlocksDevelopmentSettingsFromImportedFile() throws IOException {
        String fixture = """
            DOCKER_COMPOSE_ENABLED=true
            AUTH_EXPOSE_DEV_OTP=true
            SERVER_ERROR_INCLUDE_MESSAGE=always
            SERVER_ERROR_INCLUDE_STACKTRACE=always
            HEALTH_SHOW_DETAILS=always
            """;
        StandardEnvironment environment = loadEnvironment(fixture, Map.of(), Map.of(), "--spring.profiles.active=prod");
        for (String property : List.of("spring.datasource.url", "spring.data.redis.host", "spring.data.redis.port")) {
            assertThrows(IllegalArgumentException.class, () -> environment.getRequiredProperty(property), property);
        }
        assertFalse(environment.getRequiredProperty("spring.docker.compose.enabled", Boolean.class));
        assertFalse(environment.getRequiredProperty("spring.devtools.restart.enabled", Boolean.class));
        assertFalse(environment.getRequiredProperty("spring.devtools.add-properties", Boolean.class));
        assertFalse(environment.getRequiredProperty("app.auth.expose-development-code", Boolean.class));
        assertEquals("never", environment.getRequiredProperty("server.error.include-message"));
        assertEquals("never", environment.getRequiredProperty("server.error.include-stacktrace"));
        assertEquals("never", environment.getRequiredProperty("management.endpoint.health.show-details"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "app.security.jwt-secret",
            "app.security.access-token-ttl-seconds",
            "app.security.refresh-token-ttl-seconds",
            "app.guest.token-limit",
            "spring.task.scheduling.pool.size"}
    )
    void missingConfigurationHasNoRuntimeFallback(
        String property)
        throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> environment.getRequiredProperty(property));
        for (String profile : List.of("dev", "prod", "docker")) {
            StandardEnvironment selected =
                loadEnvironment(FIXTURE_ENV, Map.of(), Map.of(), "--spring.profiles.active=" + profile);
            assertThrows(IllegalArgumentException.class, () -> selected.getRequiredProperty(property), profile);
        }
    }

    @Test
    void completeIntegrationFixtureResolvesAllApplicationPropertiesAndDisablesExternalWork() throws IOException {
        assertCompleteIntegrationFixture(loadEnvironment(integrationFixture(), Map.of(), Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "docker"})
    void completeIntegrationFixtureResolvesCommonAndProfileProperties(
        String profile)
        throws IOException {
        StandardEnvironment environment =
            loadEnvironment(integrationFixture(), Map.of(), Map.of(), "--spring.profiles.active=" + profile);
        assertCompleteIntegrationFixture(environment);
        assertEquals(10000, environment.getRequiredProperty("app.guest.token-limit", Integer.class));
        assertEquals(3, environment.getRequiredProperty("spring.task.scheduling.pool.size", Integer.class));
        assertEquals("fixture-database-password", environment.getRequiredProperty("spring.datasource.password"));
        assertEquals(
            "fixture-payment-secret-used-only-by-tests",
            environment.getRequiredProperty("app.payment.webhook-secret")
        );
    }

    private String integrationFixture() throws IOException {
        try (var input = new ClassPathResource("test.env").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertCompleteIntegrationFixture(
        StandardEnvironment environment) {
        List<String> properties = new ArrayList<>();
        for (var source : environment.getPropertySources()) {
            if (CONFIG_FILES.stream().anyMatch(file -> source.getName().contains(file))
                && source instanceof EnumerablePropertySource<?> yaml) {
                properties.addAll(List.of(yaml.getPropertyNames()));
            }
        }
        assertFalse(properties.isEmpty());
        for (String property : properties) {
            assertDoesNotThrow(() -> environment.getRequiredProperty(property), property);
        }
        for (String property : List.of(
            "app.ai.enabled",
            "app.mail.enabled",
            "app.ocr.enabled",
            "app.jobs.collection.enabled",
            "app.jobs.expiration.enabled",
            "app.jobs.embedding.enabled",
            "app.billing.maintenance-enabled",
            "spring.docker.compose.enabled",
            "management.health.redis.enabled",
            "management.health.mail.enabled"
        )) {
            assertFalse(environment.getRequiredProperty(property, Boolean.class), property);
        }
        assertEquals("none", environment.getRequiredProperty("spring.ai.model.chat"));
        assertEquals("none", environment.getRequiredProperty("spring.ai.model.embedding"));
        JobCollectionProperties collection =
            Binder.get(environment).bind("app.jobs.collection", JobCollectionProperties.class).get();
        assertFalse(collection.isEnabled());
        assertNull(collection.getActorId());
        assertEquals(
            List.of("ITVIEC", "ITVIEC", "ITVIEC", "TOPCV", "TOPCV", "TOPCV"),
            collection.getSources().stream().map(JobCollectionProperties.Source::getSourceSite).toList()
        );
    }

    private StandardEnvironment loadEnvironment(
        Map<String, Object> processVariables,
        Map<String, Object> systemProperties,
        String... commandLineArguments)
        throws IOException {
        return loadEnvironment(FIXTURE_ENV, processVariables, systemProperties, commandLineArguments);
    }

    private StandardEnvironment loadEnvironment(
        String fixtureContents,
        Map<String, Object> processVariables,
        Map<String, Object> systemProperties,
        String... commandLineArguments)
        throws IOException {
        Path fixtureEnv = directory.resolve("settings with spaces.env");
        Files.writeString(fixtureEnv, fixtureContents, StandardCharsets.UTF_8);
        for (String configFile : CONFIG_FILES) {
            String yaml;
            try (var input = new ClassPathResource(configFile).getInputStream()) {
                yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (configFile.equals("application.yaml")) {
                assertTrue(
                    yaml.contains(ENV_IMPORT),
                    "application.yaml must import the UTF-8 .env using native Boot config"
                );
            }
            Files.writeString(directory.resolve(configFile), yaml, StandardCharsets.UTF_8);
        }
        Path fixtureYaml = directory.resolve("application.yaml");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                processVariables
            )
        );
        environment.getPropertySources().replace(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties)
        );
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(commandLineArguments));
        environment.getPropertySources().addLast(
            new MapPropertySource(
                "fixtureLocation",
                Map.of(
                    "spring.config.location",
                    fixtureYaml.toUri().toString(),
                    "APP_ENV_FILE",
                    fixtureEnv.toAbsolutePath().toString().replace('\\', '/')
                )
            )
        );
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
