package com.example.intelligent_cv_matching_ai_agent.service.shared.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LocalResumeFileStorage implements ResumeFileStorage {

    private static final String KEY_PATTERN = "[0-9a-f-]{36}\\.(pdf|docx|txt|png|jpg|jpeg)";

    private final Path root;

    public LocalResumeFileStorage(
        String storagePath) {
        this(Path.of(storagePath));
    }

    public LocalResumeFileStorage(
        Path storagePath) {
        this.root = storagePath.toAbsolutePath().normalize();
    }

    @Override
    public void store(
        String key,
        byte[] content,
        String contentType) {
        Path file = resolveSafe(key);
        try {
            Files.createDirectories(root);
            Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new ApiException(503, "CV storage is unavailable");
        }
    }

    @Override
    public byte[] read(
        String key) {
        Path file = resolveSafe(key);
        try {
            Path realRoot = root.toRealPath();
            Path target = realRoot.resolve(key).normalize();
            if (!target.startsWith(realRoot) || Files.isSymbolicLink(target)) {
                throw ApiException.forbidden("Invalid file path");
            }
            return Files.readAllBytes(target);
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ApiException.notFound("Stored CV file is unavailable");
        }
    }

    @Override
    public boolean exists(
        String key,
        long maxBytes) {
        try {
            Path file = resolveSafe(key);
            return Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) > 0
                && Files.size(file) <= maxBytes;
        } catch (IOException | ApiException ex) {
            return false;
        }
    }

    @Override
    public void delete(
        String key) {
        try {
            Path file = resolveSafe(key);
            Files.deleteIfExists(file);
        } catch (IOException | ApiException ignored) {
        }
    }

    private Path resolveSafe(
        String key) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw ApiException.badRequest("Invalid storage key");
        }
        Path file = root.resolve(key).normalize();
        if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) {
            throw ApiException.badRequest("Invalid storage path");
        }
        return file;
    }
}
