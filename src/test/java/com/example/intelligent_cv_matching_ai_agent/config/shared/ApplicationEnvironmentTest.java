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

    @ParameterizedTest
    @ValueSource(strings = {"app.security.jwt-secret", "app.guest.token-limit", "spring.task.scheduling.pool.size"})
    void missingConfigurationHasNoRuntimeFallback(
        String property)
        throws IOException {
        StandardEnvironment environment = loadEnvironment(Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> environment.getRequiredProperty(property));
    }

    @Test
    void completeIntegrationFixtureResolvesAllApplicationPropertiesAndDisablesExternalWork() throws IOException {
        String fixture;
        try (var input = new ClassPathResource("test.env").getInputStream()) {
            fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        StandardEnvironment environment = loadEnvironment(fixture, Map.of(), Map.of());
        List<String> properties = new ArrayList<>();
        for (var source : environment.getPropertySources()) {
            if (source.getName().contains("application.yaml") && source instanceof EnumerablePropertySource<?> yaml) {
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
            "management.health.rabbit.enabled",
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
            List.of("ITVIEC", "TOPCV"),
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
        String yaml;
        try (var input = new ClassPathResource("application.yaml").getInputStream()) {
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.contains(ENV_IMPORT), "application.yaml must import the UTF-8 .env using native Boot config");
        Path fixtureYaml = directory.resolve("application.yaml");
        Files.writeString(fixtureYaml, yaml, StandardCharsets.UTF_8);

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
