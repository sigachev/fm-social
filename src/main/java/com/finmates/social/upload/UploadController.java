package com.finmates.social.upload;

import com.finmates.social.common.security.AuthenticatedUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api/uploads")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Uploads", description = "Presigned S3 upload URL generation — client uploads directly to S3")
public class UploadController {

    private final S3Service s3Service;
    private final AuthenticatedUser authenticatedUser;

    @Value("${fm-social.s3.max-uploads-per-presign-call:10}")
    private int maxUploadsPerPresignCall;

    @Value("${fm-social.s3.presign-rate-limit-per-minute:30}")
    private int rateLimitPerMinute;

    @Value("${fm-social.s3.allowed-content-types:image/jpeg,image/png,image/webp,image/gif}")
    private String allowedContentTypesStr;

    @Value("${fm-social.s3.allowed-extensions:jpg,jpeg,png,webp,gif}")
    private String allowedExtensionsStr;

    /** Caffeine cache: userId → request count (expires 1 min after first request in window). */
    private final Cache<Long, AtomicInteger> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    private Set<String> allowedContentTypes;
    private Set<String> allowedExtensions;

    public UploadController(S3Service s3Service, AuthenticatedUser authenticatedUser) {
        this.s3Service = s3Service;
        this.authenticatedUser = authenticatedUser;
    }

    @PostConstruct
    void init() {
        allowedContentTypes = Set.of(allowedContentTypesStr.split(","));
        allowedExtensions = Set.of(allowedExtensionsStr.split(","));
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public record PresignRequest(
            @NotNull @Min(1) @Max(10) Integer count,
            @NotBlank String contentType,
            @NotBlank String extension,
            @NotNull UploadPurpose purpose
    ) {}

    public record SinglePresignRequest(
            @NotBlank String contentType,
            @NotBlank String extension
    ) {}

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @PostMapping("/presign")
    @Operation(summary = "Batch-presign PUT URLs for post or comment media (up to 10 files)")
    @ApiResponse(responseCode = "200", description = "Presigned URLs generated")
    @ApiResponse(responseCode = "400", description = "Validation error (count/content-type/extension/purpose)")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded — Retry-After: 60")
    public ResponseEntity<Map<String, List<S3Service.PresignedUpload>>> presign(
            @Valid @RequestBody PresignRequest req) {

        Long userId = authenticatedUser.currentUserId();
        enforceRateLimit(userId);
        validateContentTypeAndExtension(req.contentType(), req.extension());

        if (req.count() < 1 || req.count() > maxUploadsPerPresignCall) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count must be between 1 and " + maxUploadsPerPresignCall);
        }

        String folder = req.purpose() == UploadPurpose.POST ? "posts" : "comments";
        String ext = req.extension().toLowerCase();

        List<S3Service.PresignedUpload> uploads = new ArrayList<>(req.count());
        for (int i = 0; i < req.count(); i++) {
            uploads.add(s3Service.createPresignedPut(userId, folder, req.contentType(), ext));
        }

        log.debug("Issued {} presigned PUT(s) for userId={} folder={}", req.count(), userId, folder);
        return ResponseEntity.ok(Map.of("uploads", uploads));
    }

    @PostMapping("/presign/avatar")
    @Operation(summary = "Presign a PUT URL for a profile avatar upload")
    @ApiResponse(responseCode = "200", description = "Presigned URL generated")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public ResponseEntity<S3Service.PresignedUpload> presignAvatar(
            @Valid @RequestBody SinglePresignRequest req) {

        Long userId = authenticatedUser.currentUserId();
        enforceRateLimit(userId);
        validateContentTypeAndExtension(req.contentType(), req.extension());

        String folder = "users/" + userId + "/profile";
        S3Service.PresignedUpload upload = s3Service.createPresignedPut(
                userId, folder, req.contentType(), req.extension().toLowerCase());

        log.debug("Issued avatar presigned PUT for userId={}", userId);
        return ResponseEntity.ok(upload);
    }

    @PostMapping("/presign/cover")
    @Operation(summary = "Presign a PUT URL for a profile cover image upload")
    @ApiResponse(responseCode = "200", description = "Presigned URL generated")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public ResponseEntity<S3Service.PresignedUpload> presignCover(
            @Valid @RequestBody SinglePresignRequest req) {

        Long userId = authenticatedUser.currentUserId();
        enforceRateLimit(userId);
        validateContentTypeAndExtension(req.contentType(), req.extension());

        String folder = "users/" + userId + "/cover";
        S3Service.PresignedUpload upload = s3Service.createPresignedPut(
                userId, folder, req.contentType(), req.extension().toLowerCase());

        log.debug("Issued cover presigned PUT for userId={}", userId);
        return ResponseEntity.ok(upload);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Increments the per-user counter and throws 429 if the limit is exceeded. */
    private void enforceRateLimit(Long userId) {
        AtomicInteger counter = rateLimitCache.get(userId, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > rateLimitPerMinute) {
            log.warn("Presign rate limit exceeded for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many presign requests — retry after 60 seconds") {
                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
                    h.set("Retry-After", "60");
                    return h;
                }
            };
        }
    }

    private void validateContentTypeAndExtension(String contentType, String extension) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Content type not allowed: " + contentType);
        }
        if (!allowedExtensions.contains(extension.toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Extension not allowed: " + extension);
        }
    }
}
