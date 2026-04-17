package com.finmates.social.post.dto;

import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Post API response. Raw S3 keys are never exposed — {@code mediaUrls} contains
 * presigned GET URLs (1h TTL) generated fresh on every request.
 * {@code authorDisplayName} and {@code authorAvatarUrl} are resolved from the author's
 * profile at read time (30s Caffeine cache in PostService) so callers need no extra lookup.
 */
public record PostResponse(
        Long id,
        Long authorId,
        String authorUsername,
        String authorDisplayName,
        String authorAvatarUrl,
        String content,
        List<String> mediaUrls,
        PostStatus status,
        PostVisibility visibility,
        int commentCount,
        int reactionCount,
        int editCount,
        OffsetDateTime lastEditedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
