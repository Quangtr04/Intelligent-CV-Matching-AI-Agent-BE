package com.example.intelligent_cv_matching_ai_agent.service.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumePdfHtml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Offline Chromium printing preserves the CSS of administrator-approved HTML templates. */
@Component
public class ResumePdfRenderer {

    private final String browserPath;

    private final Duration timeout;

    private final boolean noSandbox;

    private final Semaphore slots;

    public ResumePdfRenderer(
        @Value("${app.resume.pdf.browser-path:}")
        String browserPath,
        @Value("${app.resume.pdf.timeout-seconds:45}")
        int timeoutSeconds,
        @Value("${app.resume.pdf.max-concurrent:2}")
        int concurrency,
        @Value("${app.resume.pdf.no-sandbox:false}")
        boolean noSandbox) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300 || concurrency < 1 || concurrency > 8) {
            throw new IllegalArgumentException("PDF timeout must be 1..300 seconds and concurrency 1..8");
        }
        this.browserPath = browserPath;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.slots = new Semaphore(concurrency);
        this.noSandbox = noSandbox;
    }

    public byte[] render(
        Map<String, Object> resume,
        String template) {
        if (!slots.tryAcquire()) {
            throw new ApiException(429, "PDF export is busy; retry shortly");
        }
        Path temporary = null;
        Process process = null;
        try {
            String executable = executable();
            String html = ResumePdfHtml.render(resume, template);
            temporary = Files.createTempDirectory("edunexus-resume-pdf-");
            Path input = temporary.resolve("resume.html");
            Path output = temporary.resolve("resume.pdf");
            Files.writeString(input, html, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(
                List.of(
                    executable,
                    "--headless=new",
                    "--disable-gpu",
                    "--disable-dev-shm-usage",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-extensions",
                    "--disable-background-networking",
                    "--disable-sync",
                    "--disable-background-mode",
                    "--no-pdf-header-footer",
                    "--print-to-pdf-no-header",
                    "--host-resolver-rules=MAP * ~NOTFOUND",
                    "--user-data-dir=" + temporary.resolve("profile"),
                    "--print-to-pdf=" + output
                )
            );
            if (noSandbox) {
                command.add("--no-sandbox");
            }
            command.add(input.toUri().toString());
            process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("browser.log").toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ApiException(504, "PDF export timed out; retry with a shorter CV");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0
                || Files.size(output) > 20 * 1024 * 1024) {
                throw new ApiException(503, "PDF renderer could not produce a valid document");
            }
            byte[] bytes = Files.readAllBytes(output);
            try (var document = Loader.loadPDF(bytes)) {
                if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > 50) {
                    throw new ApiException(413, "Exported CV exceeds 50 pages");
                }
            }
            return bytes;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "PDF export was interrupted");
        } catch (IOException exception) {
            throw new ApiException(503, "PDF renderer is unavailable; configure APP_PDF_BROWSER_PATH");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
            cleanup(temporary);
            slots.release();
        }
    }

    private String executable() {
        if (browserPath != null && !browserPath.isBlank()) {
            return browserPath;
        }
        for (String candidate : List.of(
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome",
            "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
        )) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        throw new ApiException(503, "Install Chromium or configure APP_PDF_BROWSER_PATH for PDF export");
    }

    private static void cleanup(
        Path directory) {
        if (directory == null) {
            return;
        }
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A browser helper can briefly retain its private profile on Windows.
                }
            });
        } catch (IOException ignored) {
            // Temporary cleanup must not fail an otherwise valid export.
        }
    }
}
