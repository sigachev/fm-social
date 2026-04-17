package com.finmates.social.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Observability-only job: counts stale _pending/ objects that are older than 25 hours.
 *
 * <p>Does NOT delete anything — the S3 lifecycle rule on the bucket handles actual deletion
 * (1-day rule on all _pending/ prefixes). This job just logs a warning so that operators
 * can spot abnormally large queues of abandoned uploads.
 *
 * <p>Note: {@code listObjectsV2} returns up to 1000 objects per call. For very large buckets
 * with thousands of stale uploads, pagination would be required for an exact count. At current
 * scale, a single page is sufficient for an indicative warning.
 */
@Slf4j
@Service
public class PendingUploadsCleanupJob {

    private final S3Client s3Client;

    @Value("${fm-social.s3.bucket:finmates-media}")
    private String bucket;

    public PendingUploadsCleanupJob(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Scheduled(cron = "0 0 3 * * *")  // 3 AM daily
    public void cleanupStalePendingUploads() {
        try {
            Instant cutoff = Instant.now().minus(25, ChronoUnit.HOURS);
            long staleCount = 0;

            // Posts pending uploads
            staleCount += countStale("posts/_pending/", cutoff);

            // Users pending uploads (avatar and cover) — filter keys containing /_pending/
            staleCount += countStaleUsersPending(cutoff);

            if (staleCount > 0) {
                log.warn("PendingUploadsCleanupJob: {} stale _pending/ object(s) found " +
                         "(older than 25h). S3 lifecycle rule will delete them automatically.",
                         staleCount);
            } else {
                log.info("PendingUploadsCleanupJob: no stale pending uploads found.");
            }
        } catch (Exception e) {
            log.error("PendingUploadsCleanupJob failed — will retry tomorrow: {}", e.getMessage());
        }
    }

    private long countStale(String prefix, Instant cutoff) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
            return response.contents().stream()
                    .filter(obj -> obj.lastModified().isBefore(cutoff))
                    .count();
        } catch (SdkException e) {
            log.error("Failed to list S3 objects with prefix={}: {}", prefix, e.getMessage());
            return 0;
        }
    }

    private long countStaleUsersPending(Instant cutoff) {
        try {
            ListObjectsV2Response response = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix("users/").build());
            return response.contents().stream()
                    .filter(obj -> obj.key().contains("/_pending/"))
                    .filter(obj -> obj.lastModified().isBefore(cutoff))
                    .count();
        } catch (SdkException e) {
            log.error("Failed to list S3 users/ objects: {}", e.getMessage());
            return 0;
        }
    }
}
