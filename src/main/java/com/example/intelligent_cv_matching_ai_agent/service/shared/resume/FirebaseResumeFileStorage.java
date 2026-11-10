package com.example.intelligent_cv_matching_ai_agent.service.shared.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

public class FirebaseResumeFileStorage implements ResumeFileStorage {

    private static final String KEY_PATTERN = "[0-9a-f-]{36}\\.(pdf|docx|txt|png|jpg|jpeg)";

    private final Storage storage;

    private final String bucketName;

    private final String prefix;

    public FirebaseResumeFileStorage(
        Storage storage,
        String bucketName,
        String prefix) {
        if (storage == null) {
            throw new IllegalArgumentException("Google Cloud Storage client is required");
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("Firebase storage bucket name is required");
        }
        this.storage = storage;
        this.bucketName = normalizeBucket(bucketName);
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public void store(
        String key,
        byte[] content,
        String contentType) {
        BlobId blobId = blobId(key);
        String resolvedType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        BlobInfo info = BlobInfo.newBuilder(blobId).setContentType(resolvedType).build();
        try {
            storage.create(info, content, Storage.BlobTargetOption.doesNotExist());
        } catch (StorageException ex) {
            throw new ApiException(503, "CV storage is unavailable");
        }
    }

    @Override
    public byte[] read(
        String key) {
        BlobId blobId = blobId(key);
        try {
            Blob blob = storage.get(blobId);
            if (blob == null || !blob.exists()) {
                throw ApiException.notFound("Stored CV file is unavailable");
            }
            return blob.getContent();
        } catch (ApiException ex) {
            throw ex;
        } catch (StorageException ex) {
            if (ex.getCode() == 404) {
                throw ApiException.notFound("Stored CV file is unavailable");
            }
            throw new ApiException(503, "CV storage is unavailable");
        }
    }

    @Override
    public boolean exists(
        String key,
        long maxBytes) {
        try {
            BlobId blobId = blobId(key);
            Blob blob = storage.get(blobId);
            return blob != null && blob.exists() && blob.getSize() != null && blob.getSize() > 0
                && blob.getSize() <= maxBytes;
        } catch (StorageException | ApiException ex) {
            return false;
        }
    }

    @Override
    public void delete(
        String key) {
        try {
            BlobId blobId = blobId(key);
            storage.delete(blobId);
        } catch (StorageException | ApiException ignored) {
        }
    }

    public String objectName(
        String key) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw ApiException.badRequest("Invalid storage key");
        }
        return prefix.isEmpty() ? key : prefix + "/" + key;
    }

    private BlobId blobId(
        String key) {
        return BlobId.of(bucketName, objectName(key));
    }

    private static String normalizeBucket(
        String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("gs://")) {
            trimmed = trimmed.substring(5);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Firebase storage bucket name is required");
        }
        return trimmed;
    }

    private static String normalizePrefix(
        String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.strip().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("Invalid Firebase storage prefix");
        }
        return trimmed;
    }
}
