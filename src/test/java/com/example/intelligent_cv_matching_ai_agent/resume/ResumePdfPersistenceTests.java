package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumePdfExportRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfRenderer;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumePdfHtml;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL persistence and commit callbacks in a disposable database. Only
 * Chromium rendering is mocked; the private file store, JDBC repository and
 * transaction manager are real. No application database or live provider is used.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResumePdfPersistenceTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final byte[] PDF = pdfBytes();

    private final UUID owner = UUID.randomUUID();

    private final UUID id = UUID.randomUUID();

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final ResumePdfRenderer renderer = mock(ResumePdfRenderer.class);

    @TempDir
    private Path storage;

    private String schema;

    private DriverManagerDataSource database;

    private DriverManagerDataSource scoped;

    private JdbcTemplate jdbc;

    private SqlStore sql;

    private ResumePdfExportRepository repository;

    private ResumePdfExportService service;

    private TransactionTemplate transaction;

    @BeforeEach
    void preparePrivateSchemaAndStorage() throws Exception {
        database = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        schema = "resume_pdf_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);
        scoped = schemaDataSource();
        jdbc = new JdbcTemplate(scoped);
        jdbc.setQueryTimeout(5);

        String initial = migration("V1__edunexus_complete_schema.sql");
        for (String table : List.of("users", "resumes", "resume_templates")) {
            Matcher definition = Pattern
                .compile("CREATE TABLE " + Pattern.quote(table) + " \\(.*?\\n\\);", Pattern.DOTALL).matcher(initial);
            assertTrue(definition.find(), "Use the original table definition for " + table);
            jdbc.execute(definition.group());
        }
        Matcher originalPdfColumns =
            Pattern.compile("(?m)^ALTER TABLE resumes ADD COLUMN (?:original_file_url|pdf_input_hash) [^;]+;")
                .matcher(migration("V24__resume_pdf_exports_and_analysis_cache.sql"));
        int appliedColumns = 0;
        while (originalPdfColumns.find()) {
            jdbc.execute(originalPdfColumns.group());
            appliedColumns++;
        }
        assertEquals(2, appliedColumns, "Only the two existing V24 PDF state columns are required");
        Matcher originalHtmlColumn = Pattern.compile("(?m)^ALTER TABLE resume_templates ADD COLUMN html_content text;")
            .matcher(migration("V14__html_resume_templates.sql"));
        assertTrue(originalHtmlColumn.find());
        jdbc.execute(originalHtmlColumn.group());

        sql = new SqlStore(jdbc);
        repository = new ResumePdfExportRepository(sql);
        var manager = new DataSourceTransactionManager(scoped);
        transaction = new TransactionTemplate(manager);
        service =
            new ResumePdfExportService(repository, new CurrentUser(), renderer, mapper, manager, storage.toString());
        jdbc.update(
            "INSERT INTO users(id,email,full_name,role,status) VALUES(?,?,?,'CANDIDATE','ACTIVE')",
            owner,
            owner + "@example.test",
            "Synthetic PDF Candidate"
        );
        login(owner);
        when(renderer.render(anyMap(), anyString())).thenReturn(PDF);
    }

    @AfterEach
    void removeOnlyThePrivateFixtureSchema() {
        SecurityContextHolder.clearContext();
        if (database != null && schema != null) {
            assertTrue(schema.matches("resume_pdf_[0-9a-f]{32}"));
            new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void generatedPdfMetadataPersistsAndRepeatedDownloadsReuseTheSamePrivateFile() throws Exception {
        seed("MANUAL", null, null, null, null);

        Map<String, Object> first = service.export(id);
        Map<String, Object> stored = repository.resume(id);
        String key = first.get("file_url").toString();

        assertEquals("MANUAL", stored.get("source"));
        assertEquals("READY", stored.get("status"));
        assertEquals(owner, stored.get("user_id"));
        assertEquals(key, stored.get("file_url"));
        assertEquals("Candidate_CV.pdf", stored.get("file_name"));
        assertEquals("application/pdf", stored.get("mime_type"));
        assertEquals((long) PDF.length, ((Number) stored.get("file_size_bytes")).longValue());
        assertEquals(ResumePdfHtml.inputHash(mapper, stored, ""), stored.get("pdf_input_hash"));
        assertNull(stored.get("original_file_url"));
        assertArrayEquals(PDF, Files.readAllBytes(storage.resolve(key)));

        assertArrayEquals(PDF, service.download(id).content());
        assertArrayEquals(PDF, service.download(id).content());
        assertEquals(key, repository.resume(id).get("file_url"));
        verify(renderer, times(1)).render(anyMap(), anyString());
    }

    @Test
    void unchangedUploadedPdfRetainsItsOriginalBytesAndNeverInvokesTheRenderer() throws Exception {
        String original = UUID.randomUUID() + ".pdf";
        Files.write(storage.resolve(original), PDF);
        seed("UPLOAD", original, original, "application/pdf", (long) PDF.length);
        Map<String, Object> uploaded = repository.resume(id);
        jdbc.update(
            "UPDATE resumes SET pdf_input_hash=? WHERE id=?",
            ResumePdfHtml.inputHash(mapper, uploaded, ""),
            id
        );

        assertEquals(true, service.export(id).get("cached"));
        assertArrayEquals(PDF, service.download(id).content());

        Map<String, Object> stored = repository.resume(id);
        assertEquals("UPLOAD", stored.get("source"));
        assertEquals(original, stored.get("file_url"));
        assertEquals(original, stored.get("original_file_url"));
        assertArrayEquals(PDF, Files.readAllBytes(storage.resolve(original)));
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "docx"})
    void legacyUploadKeepsItsOriginalPointerAndBytesWhenPublishedAsPdf(
        String extension)
        throws Exception {
        byte[] originalBytes = "pdf".equals(extension) ? PDF : "Original DOCX bytes".getBytes(StandardCharsets.UTF_8);
        String original = UUID.randomUUID() + "." + extension;
        Files.write(storage.resolve(original), originalBytes);
        String mime = "pdf".equals(extension)
            ? "application/pdf"
            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        // A legacy upload with no original_file_url is backfilled by the real repository update.
        seed("UPLOAD", original, null, mime, (long) originalBytes.length);

        Map<String, Object> exported = service.export(id);
        Map<String, Object> stored = repository.resume(id);

        assertNotEquals(original, exported.get("file_url"));
        assertEquals(original, stored.get("original_file_url"));
        assertEquals("UPLOAD", stored.get("source"));
        assertEquals("application/pdf", stored.get("mime_type"));
        assertArrayEquals(originalBytes, Files.readAllBytes(storage.resolve(original)));
        assertArrayEquals(PDF, service.download(id).content());
        verify(renderer, times(1)).render(anyMap(), anyString());
    }

    @Test
    void replacingPdfDeletesTheOldFileOnlyAfterTheDatabaseTransactionCommits() throws Exception {
        seed("MANUAL", null, null, null, null);
        String first = service.export(id).get("file_url").toString();
        AtomicReference<String> replacement = new AtomicReference<>();

        transaction.executeWithoutResult(status -> {
            changeSummary("Updated reviewed experience");
            String next = service.export(id).get("file_url").toString();
            replacement.set(next);
            assertNotEquals(first, next);
            assertTrue(Files.isRegularFile(storage.resolve(first)), "The prior file remains until outer commit");
            assertTrue(Files.isRegularFile(storage.resolve(next)));
            assertEquals(next, repository.resume(id).get("file_url"), "The current transaction sees its update");
            assertEquals(first, independentStoredKey(), "A separate connection cannot see an uncommitted pointer");
        });

        assertFalse(Files.exists(storage.resolve(first)));
        assertTrue(Files.isRegularFile(storage.resolve(replacement.get())));
        assertEquals(replacement.get(), repository.resume(id).get("file_url"));
        assertArrayEquals(PDF, service.download(id).content());
        verify(renderer, times(2)).render(anyMap(), anyString());
    }

    @Test
    void rollbackRemovesTheUnpublishedReplacementAndRestoresTheOldPointerAndContent() throws Exception {
        seed("MANUAL", null, null, null, null);
        Map<String, Object> first = service.export(id);
        String previous = first.get("file_url").toString();
        AtomicReference<String> abandoned = new AtomicReference<>();
        IllegalStateException rollback = new IllegalStateException("Synthetic outer transaction rollback");

        assertSame(
            rollback,
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                changeSummary("This edit must roll back");
                abandoned.set(service.export(id).get("file_url").toString());
                assertTrue(Files.isRegularFile(storage.resolve(previous)));
                assertTrue(Files.isRegularFile(storage.resolve(abandoned.get())));
                assertEquals(previous, independentStoredKey());
                throw rollback;
            }))
        );

        Map<String, Object> stored = repository.resume(id);
        assertEquals(previous, stored.get("file_url"));
        assertEquals("Reviewed Java experience", ((Map<?, ?>) stored.get("structured_data")).get("summary"));
        assertTrue(Files.isRegularFile(storage.resolve(previous)));
        assertFalse(Files.exists(storage.resolve(abandoned.get())));
        assertArrayEquals(PDF, service.download(id).content());
        verify(renderer, times(2)).render(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-owner", "DRAFT", "REVIEW_REQUIRED", "ARCHIVED"})
    void cachedPdfCannotBypassCurrentOwnershipOrReadyState(
        String invalid)
        throws Exception {
        seed("MANUAL", null, null, null, null);
        String key = service.export(id).get("file_url").toString();
        clearInvocations(renderer);
        if ("foreign-owner".equals(invalid)) {
            login(UUID.randomUUID());
        } else {
            jdbc.update("UPDATE resumes SET status=? WHERE id=?", invalid, id);
        }

        ApiException failure = assertThrows(ApiException.class, () -> service.download(id));

        assertEquals("foreign-owner".equals(invalid) ? 404 : 409, failure.status());
        assertEquals(key, repository.resume(id).get("file_url"));
        assertTrue(Files.isRegularFile(storage.resolve(key)));
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "owner", "status"})
    void aConcurrentDatabaseEditDuringRenderingCannotPublishAStalePdf(
        String change)
        throws Exception {
        seed("MANUAL", null, null, null, null);
        String old = service.export(id).get("file_url").toString();
        changeSummary("Version requested before concurrent edit");
        when(renderer.render(anyMap(), anyString())).thenAnswer(invocation -> {
            switch (change) {
                case "content" -> changeSummary("Newer concurrently saved content");
                case "owner" -> jdbc.update("UPDATE resumes SET user_id=? WHERE id=?", UUID.randomUUID(), id);
                case "status" -> jdbc.update("UPDATE resumes SET status='REVIEW_REQUIRED' WHERE id=?", id);
                default -> fail("Unexpected fixture branch");
            }
            return PDF;
        });

        ApiException failure = assertThrows(ApiException.class, () -> service.export(id));

        assertEquals("owner".equals(change) ? 404 : 409, failure.status());
        assertEquals(old, repository.resume(id).get("file_url"));
        assertTrue(Files.isRegularFile(storage.resolve(old)));
        try (var files = Files.list(storage)) {
            assertEquals(1, files.count(), "Only the earlier published file may survive");
        }
    }

    private DriverManagerDataSource schemaDataSource() {
        DriverManagerDataSource source =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        source.setConnectionProperties(properties);
        return source;
    }

    private String independentStoredKey() {
        // A distinct DataSource avoids Spring's transaction-bound connection.
        return new JdbcTemplate(schemaDataSource())
            .queryForObject("SELECT file_url FROM resumes WHERE id=?", String.class, id);
    }

    private void seed(
        String origin,
        String file,
        String original,
        String mime,
        Long size) {
        sql.insert(
            "resumes",
            com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields(
                "id",
                id,
                "user_id",
                owner,
                "title",
                "Candidate CV",
                "source",
                origin,
                "status",
                "READY",
                "language",
                "en",
                "structured_data",
                sql.json(Map.of("summary", "Reviewed Java experience")),
                "raw_content",
                "Original extraction",
                "file_url",
                file,
                "original_file_url",
                original,
                "file_name",
                "Uploaded CV",
                "mime_type",
                mime,
                "file_size_bytes",
                size
            )
        );
    }

    private void changeSummary(
        String summary) {
        sql.update("UPDATE resumes SET structured_data=? WHERE id=?", sql.json(Map.of("summary", summary)), id);
    }

    private static String migration(
        String filename)
        throws Exception {
        return new ClassPathResource("db/migration/" + filename).getContentAsString(StandardCharsets.UTF_8);
    }

    private static void login(
        UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                id.toString(),
                "",
                List.of(new SimpleGrantedAuthority("ROLE_CANDIDATE"))
            )
        );
    }

    private static byte[] pdfBytes() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(bytes);
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create the synthetic PDF fixture", exception);
        }
    }
}
