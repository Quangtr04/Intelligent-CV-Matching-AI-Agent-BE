package com.example.intelligent_cv_matching_ai_agent.config.shared;

import com.example.intelligent_cv_matching_ai_agent.config.shared.resume.ResumeStorageProperties;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.FirebaseResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.LocalResumeFileStorage;
import com.example.intelligent_cv_matching_ai_agent.service.shared.resume.ResumeFileStorage;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResumeStorageConfig {

    private static final String FIREBASE_APP_NAME = "edunexus-resume-storage";

    @Bean
    public ResumeFileStorage resumeFileStorage(
        ResumeStorageProperties properties) {
        boolean useFirebase = isFirebaseEnabled(properties);
        if (!useFirebase) {
            return new LocalResumeFileStorage(properties.getPath());
        }
        ResumeStorageProperties.Firebase firebase = properties.getFirebase();
        if (firebase.getBucket() == null || firebase.getBucket().isBlank()) {
            throw new IllegalStateException(
                "Firebase CV storage requires FIREBASE_STORAGE_BUCKET (app.storage.firebase.bucket)"
            );
        }
        GoogleCredentials credentials = loadCredentials(firebase);
        initializeFirebaseAppIfNeeded(credentials, firebase.getBucket());
        Storage storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService();
        return new FirebaseResumeFileStorage(storage, firebase.getBucket(), firebase.getPrefix());
    }

    public static boolean isFirebaseEnabled(
        ResumeStorageProperties properties) {
        if (properties.getFirebase() != null && properties.getFirebase().isEnabled()) {
            return true;
        }
        String provider = properties.getProvider();
        return provider != null && "firebase".equals(provider.strip().toLowerCase(Locale.ROOT));
    }

    public static GoogleCredentials loadCredentials(
        ResumeStorageProperties.Firebase firebase) {
        try {
            if (firebase.getCredentialsJson() != null && !firebase.getCredentialsJson().isBlank()) {
                byte[] decoded = decodeCredentialsBytes(firebase.getCredentialsJson());
                try (InputStream stream = new ByteArrayInputStream(decoded)) {
                    return GoogleCredentials.fromStream(stream);
                }
            }
            if (firebase.getCredentialsPath() != null && !firebase.getCredentialsPath().isBlank()) {
                Path path = Path.of(firebase.getCredentialsPath().strip());
                try (InputStream stream = Files.newInputStream(path)) {
                    return GoogleCredentials.fromStream(stream);
                }
            }
            return GoogleCredentials.getApplicationDefault();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load Firebase Service Account credentials", ex);
        }
    }

    static byte[] decodeCredentialsBytes(
        String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("{")) {
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException ex) {
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static synchronized void initializeFirebaseAppIfNeeded(
        GoogleCredentials credentials,
        String bucket) {
        String normalizedBucket = bucket.strip().startsWith("gs://") ? bucket.strip().substring(5) : bucket.strip();
        boolean exists = FirebaseApp.getApps().stream().anyMatch(app -> FIREBASE_APP_NAME.equals(app.getName()));
        if (!exists) {
            FirebaseOptions options =
                FirebaseOptions.builder().setCredentials(credentials).setStorageBucket(normalizedBucket).build();
            FirebaseApp.initializeApp(options, FIREBASE_APP_NAME);
        }
    }
}
