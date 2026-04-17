package com.finmates.social.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${fm-social.s3.bucket:finmates-media}")
    private String bucket;

    @Value("${fm-social.s3.presign-put-ttl-minutes:15}")
    private long presignPutTtlMinutes;

    @Value("${fm-social.s3.presign-get-ttl-minutes:60}")
    private long presignGetTtlMinutes;

    public S3Service(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Creates a presigned PUT URL for direct client-to-S3 upload.
     * Key path: {folder}/_pending/user{userId}/{uuid}.{extension}
     */
    public PresignedUpload createPresignedPut(Long userId, String folder,
                                               String contentType, String extension) {
        String uuid = UUID.randomUUID().toString();
        String s3Key = folder + "/_pending/user" + userId + "/" + uuid + "." + extension;

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .putObjectRequest(putRequest)
                .signatureDuration(Duration.ofMinutes(presignPutTtlMinutes))
                .build();

        String presignedUrl = s3Presigner.presignPutObject(presignRequest).url().toString();
        log.debug("Created presigned PUT for key={} ttl={}min", s3Key, presignPutTtlMinutes);
        return new PresignedUpload(uuid, s3Key, presignedUrl);
    }

    /**
     * Creates a presigned GET URL for reading an object.
     * Called per request — URLs are not cached (1h TTL, always fresh).
     */
    public String createPresignedGet(String s3Key) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .getObjectRequest(getRequest)
                .signatureDuration(Duration.ofMinutes(presignGetTtlMinutes))
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Returns true if the object exists in S3, false if not found.
     * Throws on any error other than 404/NoSuchKey.
     */
    public boolean objectExists(String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            log.error("S3 headObject error for key={}: {}", s3Key, e.getMessage());
            throw e;
        } catch (SdkException e) {
            log.error("S3 client error for key={}: {}", s3Key, e.getMessage());
            throw e;
        }
    }

    /**
     * Copies an object within the same bucket. Throws on failure.
     */
    public void copy(String fromKey, String toKey) {
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(fromKey)
                    .destinationBucket(bucket).destinationKey(toKey)
                    .build());
            log.info("S3 copy succeeded: {} → {}", fromKey, toKey);
        } catch (SdkException e) {
            log.error("S3 copy failed: {} → {} — {}", fromKey, toKey, e.getMessage());
            throw e;
        }
    }

    /**
     * Deletes an object. Swallows failures with a WARN — lifecycle rules clean up stragglers.
     */
    public void delete(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
            log.info("S3 delete succeeded: {}", s3Key);
        } catch (SdkException e) {
            log.warn("S3 delete failed for key={} — lifecycle will clean up: {}", s3Key, e.getMessage());
        }
    }

    /**
     * Validates that a pending S3 key was uploaded by this user to this folder.
     * Expected prefix: {expectedFolder}/_pending/user{userId}/
     *
     * @throws IllegalArgumentException if the key doesn't match ownership
     */
    public void validateOwnership(Long userId, String s3Key, String expectedFolder) {
        String expectedPrefix = expectedFolder + "/_pending/user" + userId + "/";
        if (!s3Key.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(
                    "S3 key does not belong to user " + userId + " in folder '" + expectedFolder + "'");
        }
    }

    /** Result of a successful presign-PUT call. */
    public record PresignedUpload(String uploadId, String s3Key, String presignedUrl) {}
}
