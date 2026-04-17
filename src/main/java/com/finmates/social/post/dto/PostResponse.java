package com.finmates.social.post.dto;

import com.finmates.social.post.PostStatus;
import com.finmates.social.post.PostVisibility;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Post API response. Raw S3 keys are never exposed — {@code mediaUrls} contains
 * presigned GET URLs (1h TTL) generated fresh on every request.
 */
public record PostResponse(
        Long id,
        Long authorId,
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
