package com.example.intelligent_cv_matching_ai_agent.config.shared;

import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Prints a short status box in the console when the backend has started or failed to start.
 * Registered in the main class so it also runs when the Spring context cannot be created.
 */
public class StartupStatusListener implements ApplicationListener<SpringApplicationEvent> {

    private static final String RESET = "\u001B[0m";

    private static final String GREEN = "\u001B[1;32m";

    private static final String RED = "\u001B[1;31m";

    private static final String CYAN = "\u001B[36m";

    private static final String YELLOW = "\u001B[33m";

    @Override
    public void onApplicationEvent(
        SpringApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent ready)
            printReady(ready.getApplicationContext().getEnvironment());
        else if (event instanceof ApplicationFailedEvent failed)
            printFailed(failed.getException());
    }

    private void printReady(
        Environment env) {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String base = "http://localhost:" + port + contextPath;
        String profiles = env.getActiveProfiles().length == 0
            ? String.join(",", env.getDefaultProfiles())
            : String.join(",", env.getActiveProfiles());
        boolean ai = env.getProperty("app.ai.enabled", Boolean.class, false);

        String line = "=".repeat(64);
        System.out.println();
        System.out.println(GREEN + line + RESET);
        System.out.println(GREEN + "  BACKEND DANG CHAY  (EduNexus API)" + RESET);
        System.out.println(GREEN + line + RESET);
        System.out.println("  API base   : " + CYAN + base + "/api/v1" + RESET);
        System.out.println("  Swagger UI : " + CYAN + base + "/swagger-ui/index.html" + RESET);
        System.out.println("  Health     : " + CYAN + base + "/actuator/health" + RESET);
        System.out.println("  Profile    : " + profiles);
        System.out.println("  Database   : " + env.getProperty("spring.datasource.url", "?"));
        System.out.println(
            "  AI         : " + (ai
                ? "bat (" + env.getProperty("app.ai.provider", "?") + ")"
                : YELLOW + "tat (AI_ENABLED=false, API AI tra 503)" + RESET)
        );
        System.out.println(GREEN + line + RESET);
        System.out.println();
    }

    private void printFailed(
        Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root)
            root = root.getCause();
        String message = String.valueOf(root.getMessage());

        String line = "=".repeat(64);
        System.err.println();
        System.err.println(RED + line + RESET);
        System.err.println(RED + "  BACKEND KHONG KHOI DONG DUOC" + RESET);
        System.err.println(RED + line + RESET);
        System.err.println("  Loi goc : " + root.getClass().getSimpleName());
        Arrays.stream(message.split("\n")).limit(4).forEach(part -> System.err.println("            " + part));
        String hint = hint(message);
        if (hint != null)
            System.err.println(YELLOW + "  Goi y   : " + hint + RESET);
        System.err.println("  Chi tiet day du nam o log phia tren.");
        System.err.println(RED + line + RESET);
        System.err.println();
    }

    private String hint(
        String message) {
        if (message.contains("password authentication failed"))
            return "Sai mat khau DB: kiem tra POSTGRES_PASSWORD/DATABASE_URL trong .env.";
        if (message.contains("Connection refused") || message.contains("Connection to localhost"))
            return "Chua bat database: mo Docker Desktop roi chay 'docker compose up -d --wait postgres'.";
        if (message.contains("more than one migration"))
            return "Co file trung ' 2' do iCloud tao trong target/: xoa file do hoac Build > Rebuild Project.";
        if (message.contains("Port") && message.contains("already in use"))
            return "Cong dang bi dung: tat backend dang chay khac hoac doi SERVER_PORT trong .env.";
        if (message.contains("APP_JWT_SECRET") || message.contains("Could not resolve placeholder"))
            return "Thieu bien trong .env: so sanh .env voi .env.example va bo sung key con thieu.";
        if (message.contains("Ollama") || message.contains("embedding model must match"))
            return "Cau hinh AI sai: dat AI_PROVIDER=ollama va AI_EMBEDDING_DIMENSIONS=1024 trong .env.";
        return null;
    }
}
